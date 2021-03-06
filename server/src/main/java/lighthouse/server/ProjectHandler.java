package lighthouse.server;

import org.bitcoinj.core.Sha256Hash;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;
import lighthouse.LighthouseBackend;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.LHUtils;
import lighthouse.protocol.Project;
import lighthouse.threading.AffinityExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.security.SignatureException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static java.net.HttpURLConnection.*;

/**
 * Handler that manages all inbound requests.
 */
public class ProjectHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ProjectHandler.class);

    // Refuse to read >1mb of data.
    private static final long MAX_REQUEST_SIZE_BYTES = 1024 * 1024;

    private final LighthouseBackend backend;
    private final AffinityExecutor executor;
    private final ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;

    // This ends up being mostly the same as LighthouseBackend.pledges and exists in case we want to run the web
    // server in a different thread to the backend in future.
    public static class PledgeGroup {
        public final ObservableSet<LHProtos.Pledge> open, claimed;

        public PledgeGroup(ObservableSet<LHProtos.Pledge> open, ObservableSet<LHProtos.Pledge> claimed) {
            this.open = open;
            this.claimed = claimed;
        }
    }
    private final Map<Project, PledgeGroup> pledges = new HashMap<>();

    public ProjectHandler(LighthouseBackend backend) {
        this.backend = backend;
        // This might change in future so alias it to keep assertions simple.
        this.executor = backend.executor;
        this.projectStates = backend.mirrorProjectStates(executor);
    }

    public void sendError(HttpExchange exchange, int code) {
        try {
            log.warn("Returning HTTP error {}", code);
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        } catch (IOException e) {
            Throwables.propagate(e);
        }
    }

    public void sendSuccess(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        } catch (IOException e) {
            Throwables.propagate(e);
        }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        // TODO: Use a SimpleTimeLimiter here to implement request timeouts.
        try {
            realHandle(httpExchange);
        } catch (Exception e) {
            log.error("Error handling request from {}: {}", httpExchange.getRemoteAddress().getAddress().getHostAddress(), httpExchange.getRequestURI());
            log.error("Took exception", e);
            sendError(httpExchange, HTTP_INTERNAL_ERROR);
        }
    }

    private void realHandle(HttpExchange httpExchange) throws Exception {
        executor.checkOnThread();
        // Simulate a slow server.
        // Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);

        final String method = httpExchange.getRequestMethod();
        final String path = httpExchange.getRequestURI().toString();
        log.info("REQ: {} {}", method, path);
        if (!path.startsWith(LHUtils.HTTP_PATH_PREFIX + LHUtils.HTTP_PROJECT_PATH)) {
            sendError(httpExchange, HTTP_NOT_FOUND);
            return;
        }
        Project project = backend.getProjectFromURL(httpExchange.getRequestURI());
        if (project == null) {
            log.warn("Project URL did not match any known project", httpExchange.getRequestURI());
            sendError(httpExchange, HTTP_NOT_FOUND);
            return;
        }
        switch (method) {
            case "POST": pledgeUpload(httpExchange, project); break;
            case "GET": statusDownload(httpExchange, project); break;
            default: sendError(httpExchange, HTTP_BAD_METHOD); break;
        }
    }

    private PledgeGroup getPledgesFor(Project project) {
        PledgeGroup result = pledges.get(project);
        if (result == null) {
            ObservableSet<LHProtos.Pledge> open = backend.mirrorOpenPledges(project, executor);
            ObservableSet<LHProtos.Pledge> claimed = backend.mirrorClaimedPledges(project, executor);
            result = new PledgeGroup(open, claimed);
            pledges.put(project, result);
        }
        return result;
    }

    private void statusDownload(HttpExchange httpExchange, Project project) throws IOException, SignatureException {
        LHProtos.ProjectStatus.Builder status = LHProtos.ProjectStatus.newBuilder();
        status.setId(project.getID());
        status.setTimestamp(Instant.now().getEpochSecond());
        status.setValuePledgedSoFar(Database.getInstance().getPledgedValue(project));

        boolean authenticated = false;
        String queryParams = httpExchange.getRequestURI().getRawQuery();
        if (queryParams != null && !queryParams.isEmpty()) {
            // Why doesn't the URI API have this? That's stupid.
            Map<String, String> params = Splitter.on('&').trimResults().withKeyValueSeparator('=').split(queryParams);
            String signature = params.get("sig");
            String message = params.get("msg");
            if (signature != null && message != null) {
                signature = URLDecoder.decode(signature, "UTF-8");
                message = URLDecoder.decode(message, "UTF-8");
                log.info("Attempting to authenticate project owner");
                project.authenticateOwner(message, signature);   // throws SignatureException
                log.info("... authenticated OK");
                authenticated = true;
            }
        }

        long totalPledged = 0;
        PledgeGroup pledgeGroup = getPledgesFor(project);
        for (LHProtos.Pledge pledge : pledgeGroup.open) {
            log.info("Pledge has {} txns", pledge.getTransactionsCount());
            if (authenticated) {
                status.addPledges(pledge);
            } else {
                // Remove transactions so the contract can't be closed by anyone who requests the status.
                // In future we may wish to optionally relax this constraint so anyone who can observe the project
                // can prove to themselves the pledges really exist, and the contract can be closed by any user.
                Sha256Hash origHash = LHUtils.hashFromPledge(pledge);
                LHProtos.Pledge.Builder scrubbedPledge = pledge.toBuilder()
                        .clearTransactions()
                        .setOrigHash(ByteString.copyFrom(origHash.getBytes()));
                status.addPledges(scrubbedPledge);
            }
            totalPledged += pledge.getTotalInputValue();
        }

        // Include the full contents of claimed pledges always, as by then the contract is visible on the block
        // chain anyway and so the privacy and who-can-claim issues are gone.
        if (!pledgeGroup.claimed.isEmpty())
            checkState(pledgeGroup.open.isEmpty());
        status.addAllPledges(pledgeGroup.claimed);

        LighthouseBackend.ProjectStateInfo info = projectStates.get(project.getID());
        if (info.claimedBy != null) {
            status.setClaimedBy(ByteString.copyFrom(info.claimedBy.getBytes()));
        }

        status.setValuePledgedSoFar(totalPledged);
        final LHProtos.ProjectStatus proto = status.build();
        log.info("Replying with status: {}", proto);
        byte[] bits = proto.toByteArray();
        httpExchange.sendResponseHeaders(HTTP_OK, bits.length);
        httpExchange.getResponseBody().write(bits);
        httpExchange.close();
    }

    private void pledgeUpload(HttpExchange httpExchange, Project project) throws IOException {
        // HTTP POST to /_lighthouse/crowdfund/project/$ID should contain a serialized Pledge message.
        InputStream input = ByteStreams.limit(httpExchange.getRequestBody(), MAX_REQUEST_SIZE_BYTES);
        final LHProtos.Pledge pledge;
        try {
            pledge = LHProtos.Pledge.parseFrom(input);
        } catch (Exception e) {
            log.error("Failed to read pledge protobuf: {}", e);
            sendError(httpExchange, HTTP_INTERNAL_ERROR);
            return;
        }
        log.info("Pledge uploaded from {} for project '{}'", httpExchange.getRemoteAddress(), project);
        backend.submitPledge(project, pledge).whenCompleteAsync((p, ex) -> {
            if (ex != null || p == null) {
                log.error("Submitted pledge failed processing: " + pledge);
                sendError(httpExchange, HTTP_BAD_REQUEST);
            } else {
                log.info("Pledge accepted!");
                sendSuccess(httpExchange);
            }
        }, executor);
    }
}
