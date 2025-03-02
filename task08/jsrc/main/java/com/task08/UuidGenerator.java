package com.task08;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.events.RuleEventSource;
import com.syndicate.deployment.annotations.events.RuleEvents;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@LambdaHandler(
    lambdaName = "uuid_generator",
	roleName = "uuid_generator-role",
	isPublishVersion = true,
	aliasName = "learn",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@RuleEventSource(targetRule = "uuid_trigger")
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "bucket", value = "${target_bucket}")})
public class UuidGenerator implements RequestHandler<Object, Map<String, Object>> {
	private final ObjectMapper objectMapper = new ObjectMapper();
	public Map<String, Object> handleRequest(Object request, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX");
		String time = OffsetDateTime.now(ZoneOffset.UTC).format(formatter);

		List<String> ids = new ArrayList<>();
		for(int i = 0; i<10; i++){
			ids.add(UUID.randomUUID().toString());
		}

		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("ids", ids);


		try {
			String jsonString = objectMapper.writeValueAsString(resultMap);
			saveToBucket(time,jsonString);
		} catch (JsonProcessingException e) {
			lambdaLogger.log("An error occurred while parsing: " + e.getMessage());
		}
		return resultMap;
	}

	private void saveToBucket(String datetime,String jsonString){
		final AmazonS3 s3Client = AmazonS3Client.builder()
				.withRegion(System.getenv("region")).build();
		s3Client.putObject(System.getenv("bucket"),
				datetime,
				jsonString);
	}
}
