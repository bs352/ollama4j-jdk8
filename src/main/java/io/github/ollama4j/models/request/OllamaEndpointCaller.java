package io.github.ollama4j.models.request;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import io.github.ollama4j.OllamaAPI;
import io.github.ollama4j.exceptions.OllamaBaseException;
import io.github.ollama4j.models.response.OllamaErrorResponse;
import io.github.ollama4j.models.response.OllamaResult;
import io.github.ollama4j.utils.OllamaRequestBody;
import io.github.ollama4j.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Abstract helperclass to call the ollama api server.
 */
public abstract class OllamaEndpointCaller {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaAPI.class);

    private final String host;
    private final BasicAuth basicAuth;
    private final long requestTimeoutSeconds;
    private final boolean verbose;

    public OllamaEndpointCaller(String host, BasicAuth basicAuth, long requestTimeoutSeconds, boolean verbose) {
        this.host = host;
        this.basicAuth = basicAuth;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.verbose = verbose;
    }

    protected abstract String getEndpointSuffix();

    protected abstract boolean parseResponseAndAddToBuffer(String line, StringBuilder responseBuffer);


    /**
     * Calls the api server on the given host and endpoint suffix asynchronously, aka waiting for the response.
     *
     * @param body POST body payload
     * @return result answer given by the assistant
     * @throws OllamaBaseException  any response code than 200 has been returned
     * @throws IOException          in case the responseStream can not be read
     */
    public OllamaResult callSync(OllamaRequestBody body) throws OllamaBaseException, IOException {
        // Create Request
        long startTime = System.currentTimeMillis();
        URI uri = URI.create(this.host + getEndpointSuffix());
        HttpRequest request = createHttpRequest(uri.toString(), Method.POST);
        request.body(body.getBodyPublisher());
        if (this.verbose) LOG.info("Asking model: " + body);
        HttpResponse response = request.execute();

        int statusCode = response.getStatus();
        InputStream responseBodyStream = response.bodyStream();
        StringBuilder responseBuffer = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(responseBodyStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (statusCode == 404) {
                    LOG.warn("Status code: 404 (Not Found)");
                    OllamaErrorResponse ollamaResponseModel =
                            Utils.getObjectMapper().readValue(line, OllamaErrorResponse.class);
                    responseBuffer.append(ollamaResponseModel.getError());
                } else if (statusCode == 401) {
                    LOG.warn("Status code: 401 (Unauthorized)");
                    OllamaErrorResponse ollamaResponseModel =
                            Utils.getObjectMapper()
                                    .readValue("{\"error\":\"Unauthorized\"}", OllamaErrorResponse.class);
                    responseBuffer.append(ollamaResponseModel.getError());
                } else if (statusCode == 400) {
                    LOG.warn("Status code: 400 (Bad Request)");
                    OllamaErrorResponse ollamaResponseModel = Utils.getObjectMapper().readValue(line,
                            OllamaErrorResponse.class);
                    responseBuffer.append(ollamaResponseModel.getError());
                } else {
                    boolean finished = parseResponseAndAddToBuffer(line, responseBuffer);
                    if (finished) {
                        break;
                    }
                }
            }
        }

        if (statusCode != 200) {
            LOG.error("Status code " + statusCode);
            throw new OllamaBaseException(responseBuffer.toString());
        } else {
            long endTime = System.currentTimeMillis();
            OllamaResult ollamaResult =
                    new OllamaResult(responseBuffer.toString().trim(), endTime - startTime, statusCode);
            if (verbose) LOG.info("Model response: " + ollamaResult);
            return ollamaResult;
        }
    }

    private HttpRequest createHttpRequest(String url, Method method) {
        HttpRequest httpRequest = HttpUtil.createRequest(method, url)
                .header("Accept", "application/json")
                .header("Content-type", "application/json")
                .timeout((int) (requestTimeoutSeconds * 1000));

        if (isBasicAuthCredentialsSet()) {
            httpRequest.header("Authorization", getBasicAuthHeaderValue());
        }
        return httpRequest;
    }

    /**
     * Get basic authentication header value.
     *
     * @return basic authentication header value (encoded credentials)
     */
    private String getBasicAuthHeaderValue() {
        String credentialsToEncode = this.basicAuth.getUsername() + ":" + this.basicAuth.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentialsToEncode.getBytes());
    }

    /**
     * Check if Basic Auth credentials set.
     *
     * @return true when Basic Auth credentials set
     */
    private boolean isBasicAuthCredentialsSet() {
        return this.basicAuth != null;
    }

}
