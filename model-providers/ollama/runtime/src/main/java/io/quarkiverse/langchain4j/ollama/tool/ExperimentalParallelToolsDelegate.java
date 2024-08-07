package io.quarkiverse.langchain4j.ollama.tool;

import static io.quarkiverse.langchain4j.ollama.tool.ExperimentalMessagesUtils.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.langchain4j.Experimental;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.quarkiverse.langchain4j.data.AiStatsMessage;
import io.quarkiverse.langchain4j.ollama.*;

/**
 * This class simulates tools function on ollama server.
 * To do that, it creates a specific request on top of the user query.
 * This request ask LLM to retrieve a list of tools to use to answer to the user query and the associated response.
 * The tools come from the Tool registered to the AiService.
 * Tools inputs and the response can reference tools results. So in one call to LLM we provide the answer.
 */
@Experimental
public class ExperimentalParallelToolsDelegate implements ChatLanguageModel {
    static final PromptTemplate DEFAULT_SYSTEM_TEMPLATE = PromptTemplate
            .from("""
                    {{context}}

                    You are a helpful assistant with tool calling capabilities.

                    Given the following functions, please respond with a JSON containing "actions" and required "conclusion" fields:
                        - "actions": the list of needed tools from the given functions, and only from this list, to answer the user request. The tool Object contains 3 fields:
                            - "name": selected tool name.
                            - "inputs": selected tool's properties values matching tool's properties JSON schema.
                            - "result_id": an id to identify the result of this tool, e.g. id1.
                        - "conclusion":
                            - your final answer as a simple string that references previous results or previous tools description, e.g. "Email sent!".

                    {{tools}}

                    Inputs and conclusion fields can reference previous results using $(xxx), where xxx is a previous unique result_id, Ex: "inputs": { "arg0": $(id1) }.
                    For simple requests that does not need function calling, just answer with the conclusion field.
                    """);

    record ToolResponses(List<ToolResponse> actions, String conclusion) {
    }

    record ToolResponse(String name, Map<String, Object> inputs, String result_id) {
    }

    private final OllamaClient client;
    private final String modelName;
    private final Options options;

    public ExperimentalParallelToolsDelegate(OllamaClient client, String modelName, Options options) {
        this.client = client;
        this.modelName = modelName;
        this.options = Options.builder()
                .topP(options.topP())
                .topK(options.topK())
                .numCtx(options.numCtx())
                .numPredict(options.numPredict())
                .seed(options.seed())
                .stop(options.stop())
                .repeatPenalty(options.repeatPenalty())
                .temperature(0.0)
                .build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        ChatRequest request = ChatRequest.builder()
                .model(modelName)
                .options(options)
                .stream(false)
                .messages(OllamaMessagesUtils.toOllamaMessages(messages))
                .build();
        ChatResponse response = client.chat(request);
        AiMessage aiMessage = AiMessage.from(response.message().content());
        return Response.from(aiMessage, new TokenUsage(response.promptEvalCount(), response.evalCount()));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .model(modelName)
                .options(options)
                .format("json")
                .stream(false);

        List<Message> ollamaMessages = toOllamaGroupedMessages(messages);

        if (hasAiStatsMessage(messages)) {
            AiStatsMessage aiStatsMessage = toAiStatsMessage(messages);
            if (aiStatsMessage.text() == null) {
                throw new RuntimeException("Conclusion cannot be null or empty!");
            }
            return Response.from(withoutToolRequests(aiStatsMessage), aiStatsMessage.getTokenUsage(), FinishReason.STOP);
        }

        Message systemMessage = createSystemMessageWithTools(ollamaMessages, toolSpecifications);

        List<Message> otherMessages = ollamaMessages.stream().filter(om -> om.role() != Role.SYSTEM).toList();
        List<Message> messagesWithTools = new ArrayList<>(otherMessages.size() + 1);
        messagesWithTools.add(systemMessage);
        messagesWithTools.addAll(otherMessages);

        builder.messages(messagesWithTools);

        ChatResponse response = client.chat(builder.build());
        ToolResponses toolResponses;
        try {
            toolResponses = Json.fromJson(response.message().content(), ToolResponses.class);
        } catch (Exception e) {
            throw new RuntimeException("Ollama server did not respond with valid JSON. Please try again!");
        }

        TokenUsage tokenUsage = new TokenUsage(response.promptEvalCount(), response.evalCount());
        AiMessage aiMessage;

        if (toolResponses.conclusion != null && (toolResponses.actions == null || toolResponses.actions.isEmpty())) {
            aiMessage = AiMessage.from(toolResponses.conclusion);
        } else {
            List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();
            List<String> availableTools = toolSpecifications.stream().map(ToolSpecification::name).toList();

            for (ToolResponse toolResponse : toolResponses.actions) {
                if (!availableTools.contains(toolResponse.name)) {
                    throw new RuntimeException(String.format(
                            "Ollama server wants to call '%s' tool that is not part of the available tools %s",
                            toolResponse.name, availableTools));
                } else {
                    getToolSpecification(toolResponse, toolSpecifications)
                            .map(ts -> toToolExecutionRequest(toolResponse, ts))
                            .ifPresent(toolExecutionRequests::add);
                }
            }
            if (toolResponses.conclusion != null && !toolResponses.conclusion().isEmpty()) {
                aiMessage = new AiMessage(toolResponses.conclusion, toolExecutionRequests);
            } else {
                aiMessage = AiMessage.from(toolExecutionRequests);
            }
        }
        return Response.from(AiStatsMessage.from(aiMessage, tokenUsage), tokenUsage);
    }

    private Message createSystemMessageWithTools(List<Message> messages, List<ToolSpecification> toolSpecifications) {
        String initialSystemMessages = messages.stream().filter(sm -> sm.role() == Role.SYSTEM)
                .map(Message::content)
                .collect(Collectors.joining("\n"));
        List<Properties> tools = toolSpecifications.stream()
                .map(ts -> new Properties(ts.name(), ts.description(), ts.parameters().properties()))
                .toList();
        Prompt prompt = DEFAULT_SYSTEM_TEMPLATE.apply(
                Map.of("tools", Json.toJson(tools),
                        "context", initialSystemMessages));
        return Message.builder()
                .role(Role.SYSTEM)
                .content(prompt.text())
                .build();
    }

    record Properties(String name, String description, Map<String, Map<String, Object>> properties) {
    }

    private Optional<ToolSpecification> getToolSpecification(ToolResponse toolResponse,
            List<ToolSpecification> toolSpecifications) {
        return toolSpecifications.stream()
                .filter(ts -> ts.name().equals(toolResponse.name))
                .findFirst();
    }

    private static ToolExecutionRequest toToolExecutionRequest(
            ToolResponse toolResponse, ToolSpecification toolSpecification) {
        return ToolExecutionRequest.builder()
                .id(toolResponse.result_id)
                .name(toolSpecification.name())
                .arguments(Json.toJson(toolResponse.inputs))
                .build();
    }

}
