package io.github.ollama4j.models.response;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import io.github.ollama4j.exceptions.OllamaBaseException;
import io.github.ollama4j.models.generate.OllamaGenerateRequest;
import io.github.ollama4j.models.generate.OllamaGenerateResponseModel;
import io.github.ollama4j.utils.Utils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

@Data
@EqualsAndHashCode(callSuper = true)
@SuppressWarnings("unused")
public class OllamaAsyncResultStreamer extends Thread {
    private final Function<Method, HttpRequest> requestBuilder;
    private final OllamaGenerateRequest ollamaRequestModel;
    private final OllamaResultStream stream = new OllamaResultStream();
    private String completeResponse;


    /**
     * -- GETTER -- Returns the status of the request. Indicates if the request was successful or a
     * failure. If the request was a failure, the `getResponse()` method will return the error
     * message.
     */
    @Getter
    private boolean succeeded;

    @Setter
    private long requestTimeoutSeconds;

    /**
     * -- GETTER -- Returns the HTTP response status code for the request that was made to Ollama
     * server.
     */
    @Getter
    private int httpStatusCode;

    /**
     * -- GETTER -- Returns the response time in milliseconds.
     */
    @Getter
    private long responseTime = 0;

    public OllamaAsyncResultStreamer(
            Function<Method, HttpRequest> requestBuilder,
            OllamaGenerateRequest ollamaRequestModel,
            long requestTimeoutSeconds) {
        this.requestBuilder = requestBuilder;
        this.ollamaRequestModel = ollamaRequestModel;
        this.completeResponse = "";
        this.stream.add("");
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    @Override
    public void run() {
        ollamaRequestModel.setStream(true);
        long startTime = System.currentTimeMillis();
        HttpRequest request = requestBuilder.apply(Method.POST);
        try {
            request.body(Utils.getObjectMapper().writeValueAsString(ollamaRequestModel));
            HttpResponse response = request.execute();
            int statusCode = response.getStatus();
            this.httpStatusCode = statusCode;

            InputStream responseBodyStream = response.bodyStream();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(responseBodyStream, StandardCharsets.UTF_8))) {
                String line;
                StringBuilder responseBuffer = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (statusCode == 404) {
                        OllamaErrorResponse ollamaResponseModel =
                                Utils.getObjectMapper().readValue(line, OllamaErrorResponse.class);
                        stream.add(ollamaResponseModel.getError());
                        responseBuffer.append(ollamaResponseModel.getError());
                    } else {
                        OllamaGenerateResponseModel ollamaResponseModel =
                                Utils.getObjectMapper().readValue(line, OllamaGenerateResponseModel.class);
                        String res = ollamaResponseModel.getResponse();
                        stream.add(res);
                        if (!ollamaResponseModel.isDone()) {
                            responseBuffer.append(res);
                        }
                    }
                }

                this.succeeded = true;
                this.completeResponse = responseBuffer.toString();
                long endTime = System.currentTimeMillis();
                responseTime = endTime - startTime;
            }
            if (statusCode != 200) {
                throw new OllamaBaseException(this.completeResponse);
            }
        } catch (IOException | OllamaBaseException e) {
            this.succeeded = false;
            this.completeResponse = "[FAILED] " + e.getMessage();
        }
    }

}

