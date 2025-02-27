package com.task05;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import org.joda.time.DateTime;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "learn",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@DependsOn(name = "Events",
		resourceType = ResourceType.DYNAMODB_TABLE)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "eu-central-1"),
		@EnvironmentVariable(key = "table", value = "Events")})
public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");
	private ObjectMapper objectMapper = new ObjectMapper();

	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent requestEvent, Context context) {
		Map<String, Object> requestBody = null;
		try {
			requestBody = objectMapper.readValue(objectMapper.writeValueAsString(requestEvent), LinkedHashMap.class);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			return buildResponse(500, "{\"statusCode\": 500, \"event\": "+ e.getMessage() +"}");
		}


		String uuid = UUID.randomUUID().toString();
		int principalId = (Integer) requestBody.get("principalId");

		Map<String, Object> content = (Map<String, Object>) requestBody.get("content");

		LocalDateTime now = LocalDateTime.now();
		DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
		String time = formatter.format(now);
		/*
		String event = "{\n" +
				"    \"id\": \""+uuid+"\"" +
				"    \"principalId\": "+principalId+",\n" +
				"    \"createdAt\": \""+formatter.format(now)+"\",\n" +
				"    \"body\": "+contentNode+" \n" +
				"}";
		*/
		Map<String, AttributeValue> itemValues = getAttributesMap(uuid, principalId, content, time);

		Item item = new Item();
		item.withString("id", uuid);
		item.withInt("principalId", principalId);
		item.withString("createdAt", time);
		item.withMap("body", content);

		try {
			saveToDynamoDb(itemValues, item);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

		return buildResponse(201, "{\"statusCode\": 201, \"event\": "+ requestBody.get("content").toString() +"}");
	}

	private void saveToDynamoDb(Map<String, AttributeValue> itemValues, Item item) throws JsonProcessingException {
		final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();

		try {
			ddb.putItem(System.getenv("table"), itemValues);
		} catch (ResourceNotFoundException e) {
			System.err.format("Error: The table \"%s\" can't be found.\n", System.getenv("table"));
			System.err.println(objectMapper.writeValueAsString(itemValues));
			System.exit(1);
		} catch (AmazonServiceException e) {
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	private static Map<String, AttributeValue> getAttributesMap(String id,
																int principalId,
																Map<String, Object> content,
																String time) {
		Map<String, AttributeValue> itemValues = new HashMap<>();

		AttributeValue principalIdAttribute = new AttributeValue();
		principalIdAttribute.setN(String.valueOf(principalId));

		AttributeValue contentAttribute = new AttributeValue();
		Map<String, AttributeValue> contentAttributesMap = new HashMap<>();
		for (Map.Entry entry : content.entrySet()) {
			contentAttributesMap.put(String.valueOf(entry.getKey()), new AttributeValue((String) entry.getValue()));
		}
		contentAttribute.setM(contentAttributesMap);

		itemValues.put("id", new AttributeValue(id));
		itemValues.put("principalId", principalIdAttribute);
		itemValues.put("createdAt", new AttributeValue(time));
		itemValues.put("body", contentAttribute);
		return itemValues;
	}

	private APIGatewayV2HTTPResponse buildResponse(int statusCode, String body) {
		return APIGatewayV2HTTPResponse.builder()
				.withStatusCode(statusCode)
				.withHeaders(responseHeaders)
				.withBody(body)
				.build();
	}
}
