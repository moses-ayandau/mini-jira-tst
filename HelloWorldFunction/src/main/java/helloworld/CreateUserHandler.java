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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

public class CreateUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SnsClient snsClient = SnsClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String usersTable = System.getenv("USERS_TABLE");
    private final String snsTopicArn = System.getenv("SNS_TOPIC_ARN");
    private final String emailNotificationTopicArn = System.getenv("EMAIL_NOTIFICATION_TOPIC_ARN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse and validate user input
            User user = objectMapper.readValue(input.getBody(), User.class);
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"error\": \"Email is required\"}");
            }
            user.setUserId(UUID.randomUUID().toString());
            user.setRole(user.getRole() != null ? user.getRole() : "team_member");

            // Create attribute map for DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("userId", AttributeValue.builder().s(user.getUserId()).build());
            item.put("email", AttributeValue.builder().s(user.getEmail()).build());
            item.put("role", AttributeValue.builder().s(user.getRole()).build());
            
            // Add preferences for task notifications (default to true)
            boolean receiveNotifications = user.getReceiveNotifications() != null ? user.getReceiveNotifications() : true;
            item.put("receiveNotifications", AttributeValue.builder().bool(receiveNotifications).build());
            user.setReceiveNotifications(receiveNotifications);

            // Store user in DynamoDB
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(usersTable)
                    .item(item)
                    .build());

            // Subscribe user to email notifications if they opted in
            if (receiveNotifications) {
                // Subscribe to standard SNS topic for email notifications
                SubscribeResponse response = snsClient.subscribe(SubscribeRequest.builder()
                        .topicArn(emailNotificationTopicArn)
                        .protocol("email")
                        .endpoint(user.getEmail())
                        .returnSubscriptionArn(true)
                        .build());
                
                // Store subscription ARN in user record for future management
                // Note: For email protocol, the subscription ARN will be "pending confirmation"
                // until the user confirms via the email they receive
                if (response.subscriptionArn() != null) {
                    dynamoDbClient.updateItem(builder -> builder
                        .tableName(usersTable)
                        .key(Map.of("userId", AttributeValue.builder().s(user.getUserId()).build()))
                        .updateExpression("SET subscriptionArn = :arn")
                        .expressionAttributeValues(Map.of(":arn", AttributeValue.builder().s(response.subscriptionArn()).build())));
                }
                
                context.getLogger().log("User subscribed to notifications: " + response.subscriptionArn());
            }

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(objectMapper.writeValueAsString(user))
                    .withHeaders(Map.of("Content-Type", "application/json"));
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}