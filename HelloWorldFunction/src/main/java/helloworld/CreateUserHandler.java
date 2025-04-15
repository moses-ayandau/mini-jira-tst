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

public class CreateUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final SnsClient snsClient = SnsClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String usersTable = System.getenv("USERS_TABLE");
    private final String snsTopicArn = System.getenv("SNS_TOPIC_ARN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            User user = objectMapper.readValue(input.getBody(), User.class);
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("{\"error\": \"Email is required\"}");
            }
            user.setUserId(UUID.randomUUID().toString());
            user.setRole(user.getRole() != null ? user.getRole() : "team_member");

            // Store user in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("userId", AttributeValue.builder().s(user.getUserId()).build());
            item.put("email", AttributeValue.builder().s(user.getEmail()).build());
            item.put("role", AttributeValue.builder().s(user.getRole()).build());

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(usersTable)
                    .item(item)
                    .build());

            // Subscribe email to SNS topic
            snsClient.subscribe(SubscribeRequest.builder()
                    .topicArn(snsTopicArn)
                    .protocol("email")
                    .endpoint(user.getEmail())
                    .build());

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