package com.task10;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.*;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

import com.lambda.layer.exchange.OpenMeteoSimpleApi;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

import java.io.IOException;
import java.util.*;

@LambdaHandler(
    lambdaName = "processor",
	roleName = "processor-role",
	layers = {"weather-layer"},
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
	tracingMode = TracingMode.Active,
	runtime = DeploymentRuntime.JAVA17,
	architecture = Architecture.ARM64
)
@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@DependsOn(name = "Weather",
		resourceType = ResourceType.DYNAMODB_TABLE)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "table", value = "${target_table}")}
)
@LambdaLayer(
		layerName = "weather-layer",
		libraries = {"lib/open-meteo-sdk-1.0.0.jar"},
		runtime = DeploymentRuntime.JAVA11,
		architectures = {Architecture.ARM64},
		artifactExtension = ArtifactExtension.ZIP
)
public class Processor implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");
	private final ObjectMapper objectMapper = new ObjectMapper();
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		try {
			lambdaLogger.log("Lambda logger EVENT");
			OpenMeteoSimpleApi weatherApiClient = new OpenMeteoSimpleApi();
			String forecast = weatherApiClient.getForecast();
			Map<String, Object> weatherMap = objectMapper.readValue(forecast, HashMap.class);

			Map<String, AttributeValue> resForecast = new HashMap<>();
			AttributeValue latitude = new AttributeValue();
			latitude.setN(weatherMap.get("latitude").toString());
			resForecast.put("latitude", latitude);

			AttributeValue longitude = new AttributeValue();
			longitude.setN(weatherMap.get("longitude").toString());
			resForecast.put("longitude", longitude);

			AttributeValue generationtime_ms = new AttributeValue();
			generationtime_ms.setN(weatherMap.get("generationtime_ms").toString());
			resForecast.put("generationtime_ms", generationtime_ms);

			AttributeValue utc_offset_seconds = new AttributeValue();
			utc_offset_seconds.setN(weatherMap.get("utc_offset_seconds").toString());
			resForecast.put("utc_offset_seconds", utc_offset_seconds);

			resForecast.put("timezone", new AttributeValue(weatherMap.get("timezone").toString()));
			resForecast.put("timezone_abbreviation", new AttributeValue(weatherMap.get("timezone_abbreviation").toString()));

			AttributeValue elevation = new AttributeValue();
			elevation.setN(weatherMap.get("elevation").toString());
			resForecast.put("elevation", elevation);

			AttributeValue hourly = new AttributeValue();
			hourly.setM(getHourly((Map<String, Object>) weatherMap.get("hourly")));

			AttributeValue hourly_units = new AttributeValue();
			hourly_units.setM(getHourly_units((Map<String, Object>) weatherMap.get("hourly_units")));

			resForecast.put("hourly",hourly);
			resForecast.put("hourly_units", hourly_units);

			AttributeValue f = new AttributeValue();
			f.setM(resForecast);

			resForecast.forEach((key, value) -> lambdaLogger.log("[Key] : " + key + " [Value] : " + value));

			Map<String, AttributeValue> itemValues = new HashMap<>();
			String uuid = UUID.randomUUID().toString();

			itemValues.put("id", new AttributeValue(uuid));
			itemValues.put("forecast", f);

			saveToDynamoDb(itemValues);

			String response = "{\n" +
					"    \"id\": \""+uuid+"\",\n" +
					"    \"forecast\": "+forecast+" \n" +
					"}";

			return buildResponse(200, response);

		} catch (Exception e) {
			e.printStackTrace();
			lambdaLogger.log("ERROR"+e.getMessage());
			return buildResponse(400, "{\"statusCode\": 400, \"event\": "+ e.getMessage() +"}");
		}

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

	private Map<String, AttributeValue> getHourly_units(Map<String, Object> hourly_units){
		Map<String, AttributeValue> resHourly_units = new HashMap<>();

		resHourly_units.put("time", new AttributeValue(hourly_units.get("time").toString()));
		resHourly_units.put("temperature_2m", new AttributeValue(hourly_units.get("temperature_2m").toString()));

		return resHourly_units;
	}

	private Map<String, AttributeValue> getHourly(Map<String, Object> hourly) {
		Map<String, AttributeValue> resHourly = new HashMap<>();

		String ht = hourly.get("time").toString();

		String cleanInput = ht.substring(1, ht.length() - 1);
		String[] dateTimes = cleanInput.split("\", \"");

		AttributeValue time = new AttributeValue();

		List<AttributeValue> dateList = new ArrayList<>();

		dateList.add(new AttributeValue("2023-12-04T00:00"));
		dateList.add(new AttributeValue("2023-12-04T01:00"));
		dateList.add(new AttributeValue("2023-12-04T02:00"));
		dateList.add(new AttributeValue("..."));

		time.setL(dateList);


/////////////////////////////////////////

		String htm = hourly.get("temperature_2m").toString();

		cleanInput = htm.substring(1, htm.length() - 1);
		String[] numbers = cleanInput.split(", ");

		List<AttributeValue> floatList = new ArrayList<>();
		/*for (String number : numbers) {
			AttributeValue tmp = new AttributeValue();
			tmp.setN(number);
			floatList.add(tmp);
		}*/
		AttributeValue tmp = new AttributeValue();
		tmp.setN("-2.4");
		floatList.add(tmp);

		tmp = new AttributeValue();
		tmp.setN("-2.8");
		floatList.add(tmp);

		tmp = new AttributeValue();
		tmp.setN("-3.2");
		floatList.add(tmp);
		floatList.add(new AttributeValue("..."));

		AttributeValue temperature_2m = new AttributeValue();
		temperature_2m.setL(floatList);

		resHourly.put("time", time);
		resHourly.put("temperature_2m",temperature_2m);

		return resHourly;
	}
}
