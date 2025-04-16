package helloworld;

import java.util.Map;

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
import software.amazon.awssdk.services.sns.model.PublishRequest;

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
                TaskAssignment taskAssignment = objectMapper.readValue(message.getBody(), TaskAssignment.class);
                
                // Get user information
                GetItemResponse userResponse = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(usersTable)
                        .key(Map.of("userId", AttributeValue.builder().s(taskAssignment.getUserId()).build()))
                        .build());
                
                if (userResponse.hasItem()) {
                    Map<String, AttributeValue> user = userResponse.item();
                    
                    // Check if user is subscribed to tasks
                    boolean subscribedToTasks = user.containsKey("subscribedToTasks") && 
                                               user.get("subscribedToTasks").bool();
                    
                    if (subscribedToTasks) {
                        // Process task assignment in the system
                        // This is your system-level notification via FIFO topic
                        snsClient.publish(PublishRequest.builder()
                                .topicArn(fifoTopicArn)
                                .message(objectMapper.writeValueAsString(taskAssignment))
                                .messageGroupId(taskAssignment.getUserId())
                                .messageDeduplicationId(taskAssignment.getTaskId())
                                .build());
                        
                        // If user wants email notifications, send via standard topic
                        boolean receiveNotifications = user.containsKey("receiveNotifications") && 
                                                      user.get("receiveNotifications").bool();
                        
                        if (receiveNotifications && emailTopicArn != null) {
                            String email = user.get("email").s();
                            String taskTitle = taskAssignment.getTaskTitle();
                            String messageBody = "You have been assigned a new task: " + taskTitle;
                            
                            snsClient.publish(PublishRequest.builder()
                                    .topicArn(emailTopicArn)
                                    .subject("New Task Assignment")
                                    .message(messageBody)
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                context.getLogger().log("Error processing message: " + e.getMessage());
            }
        }
        return null;
    }
}