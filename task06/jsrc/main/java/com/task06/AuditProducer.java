package com.task06;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

import com.amazonaws.services.dynamodbv2.document.Item;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;

import com.syndicate.deployment.annotations.events.DynamoDbTriggerEventSource;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.*;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@LambdaHandler(
    lambdaName = "audit_producer",
	roleName = "audit_producer-role",
	isPublishVersion = true,
	aliasName = "learn",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@DependsOn(name = "${target_table}",
		resourceType = ResourceType.DYNAMODB_TABLE)
@DynamoDbTriggerEventSource(targetTable = "Configuration", batchSize = 1)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "table", value = "${target_table}")})
public class AuditProducer implements RequestHandler<DynamodbEvent, APIGatewayV2HTTPResponse> {

	private ObjectMapper objectMapper = new ObjectMapper();
	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");

	public APIGatewayV2HTTPResponse handleRequest(DynamodbEvent event, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		try {
			for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
				if (record == null) {
					continue;
				}

				lambdaLogger.log("record: " + record.toString());
				lambdaLogger.log("newImage: " + record.getDynamodb().getNewImage().toString());
				String uuid = UUID.randomUUID().toString();

				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX");
				String time = OffsetDateTime.now(ZoneOffset.UTC).format(formatter);

				if(record.getEventName().equalsIgnoreCase("INSERT")){
					lambdaLogger.log("INSERT");
					String key = record.getDynamodb().getNewImage().get("key").getS();
					String value = record.getDynamodb().getNewImage().get("value").getN();

					lambdaLogger.log("key: " + key);
					lambdaLogger.log("value: " + value);
					Map<String, Object> content = new HashMap<>();
					content.put("key",key);
					content.put("value",value);

					Map<String, AttributeValue> itemValues = getAttributesMap(uuid, key, time, content);

					saveToDynamoDb(itemValues);
					lambdaLogger.log("saveToDynamoDb");
					String ev = "{\n" +
							"   \"id\": "+uuid+",\n" +
							"   \"itemKey\": \""+key+"\",\n" +
							"   \"modificationTime\": \""+time+"\",\n" +
							"   \"newValue\": {\n" +
							"       \"key\": \""+key+"\",\n" +
							"       \"value\": "+value+"\n" +
							"   },\n" +
							"}";
					return buildResponse(200, "{\"statusCode\": 200, \"event\": "+ ev +"}");
				}


			}
		}catch (Exception e){

			lambdaLogger.log("messag: " + e.getMessage());
			return buildResponse(500, "{\"statusCode\": 500, \"event\": "+ e.getMessage() +"}");
		}
		return buildResponse(500, "{\"statusCode\": 500, \"event\": \"event\"}");
	}

	private static Map<String, AttributeValue> getAttributesMap(String id,
																String itemKey,
																String time,
																Map<String, Object> content) {
		Map<String, AttributeValue> itemValues = new HashMap<>();

		AttributeValue contentAttribute = new AttributeValue();
		Map<String, AttributeValue> contentAttributesMap = new HashMap<>();
		for (Map.Entry entry : content.entrySet()) {
			contentAttributesMap.put(String.valueOf(entry.getKey()), new AttributeValue((String) entry.getValue()));
		}
		contentAttribute.setM(contentAttributesMap);

		itemValues.put("id", new AttributeValue(id));
		itemValues.put("itemKey", new AttributeValue(itemKey));
		itemValues.put("modificationTime", new AttributeValue(time));
		itemValues.put("newValue", contentAttribute);
		return itemValues;
	}

	private void saveToDynamoDb(Map<String, AttributeValue> itemValues) {
		final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();
		ddb.putItem(System.getenv("table"), itemValues);
	}

	private APIGatewayV2HTTPResponse buildResponse(int statusCode, String body) {
		return APIGatewayV2HTTPResponse.builder()
				.withStatusCode(statusCode)
				.withHeaders(responseHeaders)
				.withBody(body)
				.build();
	}
}
