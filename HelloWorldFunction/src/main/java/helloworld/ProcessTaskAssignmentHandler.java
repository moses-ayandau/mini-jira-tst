package helloworld;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.Map;

public class ProcessTaskAssignmentHandler implements RequestHandler<SQSEvent, Void> {

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SnsClient snsClient = SnsClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String usersTable = System.getenv("USERS_TABLE");
    private final String snsTopicArn = System.getenv("SNS_TOPIC_ARN");

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                Task task = objectMapper.readValue(message.getBody(), Task.class);

                // Get user email from DynamoDB
                Map<String, AttributeValue> key = Map.of("userId", AttributeValue.builder().s(task.getUserId()).build());
                Map<String, AttributeValue> userItem = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(usersTable)
                        .key(key)
                        .build()).item();

                if (userItem == null || !userItem.containsKey("email")) {
                    context.getLogger().log("User not found: " + task.getUserId());
                    continue;
                }

                String email = userItem.get("email").s();

                // Publish notification to SNS
                String messageBody = String.format("Task Assigned: %s\nTask ID: %s\nDeadline: %s",
                        task.getName(), task.getTaskId(), task.getDeadline());
                snsClient.publish(PublishRequest.builder()
                        .topicArn(snsTopicArn)
                        .message(messageBody)
                        .messageAttributes(Map.of(
                                "email", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue(email)
                                        .build()
                        ))
                        .messageGroupId("task-notifications")
                        .build());

                context.getLogger().log("Notification sent for task: " + task.getTaskId());
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
            }
        }
        return null;
    }
}