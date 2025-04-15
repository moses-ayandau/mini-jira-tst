package helloworld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class CreateTaskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SqsClient sqsClient = SqsClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String tasksTable = System.getenv("TASKS_TABLE");
    private final String taskAssignmentQueue = System.getenv("TASK_ASSIGNMENT_QUEUE");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            Task task = objectMapper.readValue(input.getBody(), Task.class);
            if (task.getName() == null || task.getName().isEmpty() || 
                task.getDeadline() == null || task.getDeadline().isEmpty() || 
                task.getUserId() == null || task.getUserId().isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"error\": \"Name, deadline, and userId are required\"}");
            }
            task.setTaskId(UUID.randomUUID().toString());
            task.setStatus("open");
            task.setDescription(task.getDescription() != null ? task.getDescription() : "");

            // Store task in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("taskId", AttributeValue.builder().s(task.getTaskId()).build());
            item.put("name", AttributeValue.builder().s(task.getName()).build());
            item.put("description", AttributeValue.builder().s(task.getDescription()).build());
            item.put("status", AttributeValue.builder().s(task.getStatus()).build());
            item.put("deadline", AttributeValue.builder().s(task.getDeadline()).build());
            item.put("userId", AttributeValue.builder().s(task.getUserId()).build());

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tasksTable)
                    .item(item)
                    .build());

            // Send task assignment to SQS
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(taskAssignmentQueue)
                    .messageBody(objectMapper.writeValueAsString(task))
                    .messageGroupId("task-assignments")
                    .build());

            Map<String, String> responseBody = new HashMap<>();
            responseBody.put("taskId", task.getTaskId());
            responseBody.put("message", "Task created and queued for assignment");

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(objectMapper.writeValueAsString(responseBody))
                    .withHeaders(Map.of("Content-Type", "application/json"));
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}