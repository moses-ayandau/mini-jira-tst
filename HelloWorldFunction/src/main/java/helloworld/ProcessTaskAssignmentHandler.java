package helloworld;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.Map;
import java.util.HashMap;

public class ProcessTaskAssignmentHandler implements RequestHandler<SQSEvent, Void> {
    
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SnsClient snsClient = SnsClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String usersTable = System.getenv("USERS_TABLE");
    private final String fifoTopicArn = System.getenv("SNS_TOPIC_ARN");
    private final String emailTopicArn = System.getenv("EMAIL_NOTIFICATION_TOPIC_ARN");
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSMessage message : event.getRecords()) {
            try {
                context.getLogger().log("Processing message: " + message.getBody());
                TaskAssignment taskAssignment = objectMapper.readValue(message.getBody(), TaskAssignment.class);
                
                // Get user information
                GetItemResponse userResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(usersTable)
                        .key(Map.of("userId", AttributeValue.builder().s(taskAssignment.getUserId()).build()))
                        .build());
                
                if (userResponse.hasItem()) {
                    Map<String, AttributeValue> userItem = userResponse.item();
                    String userEmail = userItem.get("email").s();
                    String userName = userItem.containsKey("name") ? userItem.get("name").s() : "User";
                    
                    // Prepare notification message
                    String taskTitle = taskAssignment.getTaskTitle();
                    String messageBody = String.format("Hello %s, you have been assigned a new task: %s", 
                            userName, taskTitle);
                    
                    // 1. Publish to FIFO topic for system processing
                    // FIFO topics require a message group ID and deduplication ID
                    Map<String, MessageAttributeValue> fifoAttributes = new HashMap<>();
                    fifoAttributes.put("userId", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(taskAssignment.getUserId())
                            .build());
                    
                    PublishResponse fifoResponse = snsClient.publish(PublishRequest.builder()
                            .topicArn(fifoTopicArn)
                            .message(objectMapper.writeValueAsString(taskAssignment))
                            .messageGroupId(taskAssignment.getUserId())
                            .messageDeduplicationId(taskAssignment.getTaskId())
                            .messageAttributes(fifoAttributes)
                            .build());
                    
                    context.getLogger().log("Published to FIFO topic: " + fifoResponse.messageId());
                    
                    // 2. If user opted in for notifications, also publish to standard email topic
                    if (userItem.containsKey("receiveNotifications") && userItem.get("receiveNotifications").bool()) {
                        Map<String, MessageAttributeValue> emailAttributes = new HashMap<>();
                        emailAttributes.put("taskId", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(taskAssignment.getTaskId())
                                .build());
                        
                        PublishResponse emailResponse = snsClient.publish(PublishRequest.builder()
                                .topicArn(emailTopicArn)
                                .subject("New Task Assignment: " + taskTitle)
                                .message(messageBody)
                                .messageAttributes(emailAttributes)
                                .build());
                        
                        context.getLogger().log("Published to email topic: " + emailResponse.messageId());
                    }
                } else {
                    context.getLogger().log("User not found: " + taskAssignment.getUserId());
                }
                
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
            }
        }
        return null;
    }
}