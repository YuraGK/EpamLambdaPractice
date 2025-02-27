package com.task05;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

import com.amazonaws.services.dynamodbv2.document.Item;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;

import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.*;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

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
public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");
	private ObjectMapper objectMapper = new ObjectMapper();

	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent requestEvent, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		try {
			lambdaLogger.log("Lambda logger EVENT: " + objectMapper.writeValueAsString(requestEvent));
			lambdaLogger.log("EVENT TYPE: " + requestEvent.getClass());
			Map<String, Object> requestBody = objectMapper.readValue(objectMapper.writeValueAsString(requestEvent), LinkedHashMap.class);



			String uuid = UUID.randomUUID().toString();



			JsonNode jsonNode = objectMapper.readTree(requestEvent.getBody());
			int principalId = jsonNode.get("principalId").asInt();
			JsonNode contentNode = jsonNode.get("content");
			Map<String, String> content = objectMapper.readValue(contentNode.toString(), Map.class);
			lambdaLogger.log(content.toString());

			LocalDateTime now = LocalDateTime.now();
			DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
			String time = formatter.format(now);

			Map<String, AttributeValue> itemValues = getAttributesMap(uuid, principalId, content, time);

			saveToDynamoDb(itemValues);


			return buildResponse(201, "{\"statusCode\": 201, \"event\": "+ requestBody.get("content").toString() +"}");
		} catch (Exception e) {
			e.printStackTrace();
			return buildResponse(500, "{\"statusCode\": 500, \"event\": "+ e.getMessage() +"}");
		}
	}

	private void saveToDynamoDb(Map<String, AttributeValue> itemValues) {
		final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion("eu-central-1")
				.build();
		ddb.putItem("Events", itemValues);
	}

	private static Map<String, AttributeValue> getAttributesMap(String id,
																int principalId,
																Map<String, String> content,
																String time) {
		Map<String, AttributeValue> itemValues = new HashMap<>();

		AttributeValue principalIdAttribute = new AttributeValue();
		principalIdAttribute.setN(principalId+"");

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
