package io.github.ollama4j.models.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.ollama4j.exceptions.OllamaBaseException;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.response.OllamaResult;
import io.github.ollama4j.models.chat.OllamaChatResponseModel;
import io.github.ollama4j.models.chat.OllamaChatStreamObserver;
import io.github.ollama4j.models.generate.OllamaStreamHandler;
import io.github.ollama4j.utils.OllamaRequestBody;
import io.github.ollama4j.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Specialization class for requests
 */
public class OllamaChatEndpointCaller extends OllamaEndpointCaller {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaChatEndpointCaller.class);

    private OllamaChatStreamObserver streamObserver;

    public OllamaChatEndpointCaller(String host, BasicAuth basicAuth, long requestTimeoutSeconds, boolean verbose) {
        super(host, basicAuth, requestTimeoutSeconds, verbose);
    }

    @Override
    protected String getEndpointSuffix() {
        return "/api/chat";
    }

    /**
     * Parses streamed Response line from ollama chat.
     * Using {@link com.fasterxml.jackson.databind.ObjectMapper#readValue(String, TypeReference)} should throw
     * {@link IllegalArgumentException} in case of null line or {@link com.fasterxml.jackson.core.JsonParseException}
     * in case the JSON Object cannot be parsed to a {@link OllamaChatResponseModel}. Thus, the ResponseModel should
     * never be null.
     *
     * @param line streamed line of ollama stream response
     * @param responseBuffer Stringbuffer to add latest response message part to
     * @return TRUE, if ollama-Response has 'done' state
     */
    @Override
    protected boolean parseResponseAndAddToBuffer(String line, StringBuilder responseBuffer) {
        try {
            OllamaChatResponseModel ollamaResponseModel = Utils.getObjectMapper().readValue(line, OllamaChatResponseModel.class);
            // it seems that under heavy load ollama responds with an empty chat message part in the streamed response
            // thus, we null check the message and hope that the next streamed response has some message content again
            OllamaChatMessage message = ollamaResponseModel.getMessage();
            if(message != null) {
                responseBuffer.append(message.getContent());
                if (streamObserver != null) {
                    streamObserver.notify(ollamaResponseModel);
                }
            }
            return ollamaResponseModel.isDone();
        } catch (JsonProcessingException e) {
            LOG.error("Error parsing the Ollama chat response!", e);
            return true;
        }
    }

    public OllamaResult call(OllamaRequestBody body, OllamaStreamHandler streamHandler)
            throws OllamaBaseException, IOException, InterruptedException {
        streamObserver = new OllamaChatStreamObserver(streamHandler);
        return super.callSync(body);
    }
}
