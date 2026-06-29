package co.kuznetsov.mediapipe.worker;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.Slf4JLogMessageHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class TelegramClientManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramClientManager.class);
    private static final long READY_TIMEOUT_SECONDS = 60;

    private final int apiId;
    private final String apiHash;
    private final Path sessionDir;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;

    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final AtomicReference<Throwable> startupError = new AtomicReference<>();

    public TelegramClientManager(int apiId, String apiHash, Path sessionDir) {
        this.apiId = apiId;
        this.apiHash = apiHash;
        this.sessionDir = sessionDir;
    }

    public void start() throws Exception {
        Init.init();
        Log.setLogMessageHandler(1, new Slf4JLogMessageHandler());

        clientFactory = new SimpleTelegramClientFactory();

        APIToken apiToken = new APIToken(apiId, apiHash);
        TDLibSettings settings = TDLibSettings.create(apiToken);
        settings.setDatabaseDirectoryPath(sessionDir.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(sessionDir.resolve("downloads"));

        SimpleTelegramClientBuilder clientBuilder = clientFactory.builder(settings);
        clientBuilder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationState);

        client = clientBuilder.build(AuthenticationSupplier.consoleLogin());
        LOG.info("Telegram client starting, waiting for authorization...");
    }

    /**
     * Returns the ready client, blocking until it is authorized or the timeout elapses.
     *
     * @throws IllegalStateException if the client is not ready within the timeout
     */
    public SimpleTelegramClient getClient() {
        try {
            boolean ready = readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ready) {
                throw new IllegalStateException("Telegram client did not become ready within "
                    + READY_TIMEOUT_SECONDS + "s");
            }
            Throwable error = startupError.get();
            if (error != null) {
                throw new IllegalStateException("Telegram client failed to start", error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Telegram client", e);
        }
        return client;
    }

    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn("Error closing Telegram client", e);
            }
        }
        if (clientFactory != null) {
            try {
                clientFactory.close();
            } catch (Exception e) {
                LOG.warn("Error closing Telegram client factory", e);
            }
        }
    }

    private void onAuthorizationState(TdApi.UpdateAuthorizationState update) {
        TdApi.AuthorizationState state = update.authorizationState;
        if (state instanceof TdApi.AuthorizationStateReady) {
            LOG.info("Telegram client authorized and ready");
            readyLatch.countDown();
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            LOG.warn("Telegram client closed");
            startupError.compareAndSet(null, new IllegalStateException("Telegram client closed before becoming ready"));
            readyLatch.countDown();
        } else {
            LOG.debug("Telegram authorization state: {}", state.getClass().getSimpleName());
        }
    }
}
