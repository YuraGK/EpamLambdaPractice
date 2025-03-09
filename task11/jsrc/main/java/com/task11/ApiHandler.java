package com.task11;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.AdminCreateUserRequest;
import com.amazonaws.services.cognitoidp.model.AdminInitiateAuthRequest;
import com.amazonaws.services.cognitoidp.model.AdminInitiateAuthResult;
import com.amazonaws.services.cognitoidp.model.AdminSetUserPasswordRequest;
import com.amazonaws.services.cognitoidp.model.AttributeType;
import com.amazonaws.services.cognitoidp.model.AuthFlowType;
import com.amazonaws.services.cognitoidp.model.ListUserPoolClientsRequest;
import com.amazonaws.services.cognitoidp.model.ListUserPoolClientsResult;
import com.amazonaws.services.cognitoidp.model.ListUserPoolsRequest;
import com.amazonaws.services.cognitoidp.model.ListUserPoolsResult;
import com.amazonaws.services.cognitoidp.model.MessageActionType;
import com.amazonaws.services.cognitoidp.model.UserPoolClientDescription;
import com.amazonaws.services.cognitoidp.model.UserPoolDescriptionType;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.environment.ValueTransformer;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@LambdaHandler(
    lambdaName = "api_handler",
	roleName = "api_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)@LambdaUrlConfig(
		authType = AuthType.NONE,
		invokeMode = InvokeMode.BUFFERED
)
@DependsOn(name = "${tables_table}", resourceType = ResourceType.DYNAMODB_TABLE)
@DependsOn(name = "${reservations_table}", resourceType = ResourceType.DYNAMODB_TABLE)
@DependsOn(name = "${booking_userpool}", resourceType = ResourceType.COGNITO_USER_POOL)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "region", value = "${region}"),
		@EnvironmentVariable(key = "tables_table", value = "${tables_table}"),
		@EnvironmentVariable(key = "reservations_table", value = "${reservations_table}"),
		@EnvironmentVariable(key = "booking_userpool", value = "${booking_userpool}"),
		@EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID),
		@EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID)
}
)
public class ApiHandler implements RequestHandler<Map<String, Object>, APIGatewayV2HTTPResponse> {

	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");
	private final ObjectMapper objectMapper = new ObjectMapper();
	private static final String EMAIL_REGEX = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
	private final AWSCognitoIdentityProvider cognitoClient = AWSCognitoIdentityProviderClientBuilder.defaultClient();
	public APIGatewayV2HTTPResponse handleRequest(Map<String, Object> event, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		String resultBody = "{\"statusCode\": 200, \"event\": o}";

		String rawPath = (String) event.get("path");
		String method = (String) event.get("httpMethod");
		lambdaLogger.log(method+": " + rawPath);

		try {
			if ("POST".equals(method) && "/signup".equals(rawPath)) {
				resultBody = postSignup(event, lambdaLogger);
			} else if ("POST".equals(method) && "/signin".equals(rawPath)) {
				resultBody = postSignin(event, lambdaLogger);
			}else if ("GET".equals(method) && "/tables".equals(rawPath)) {
				resultBody = getTables();
			}else if ("POST".equals(method) && "/tables".equals(rawPath)) {
				resultBody = postTables(event, lambdaLogger);
			}else if ("GET".equals(method) && (rawPath.matches("/tables/\\d+"))) {
				String tableId = rawPath.substring("/tables/".length());
				resultBody = getTableById(tableId);
			}else if ("POST".equals(method) && "/reservations".equals(rawPath)) {
				resultBody = postReservations(event, lambdaLogger);
			}else if ("GET".equals(method) && "/reservations".equals(rawPath)) {
				resultBody = getReservations();
			}


		}catch (Exception e) {
			e.printStackTrace();
			lambdaLogger.log("ERROR "+e.getMessage());
			return buildResponse(400, "{\"statusCode\": 400, \"event\": "+ e.getMessage() +"}");
		}

		return buildResponse(200, resultBody);
	}




	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private String postSignup(Map<String, Object> requestEvent, LambdaLogger logger) throws JsonProcessingException {
		Map<String, Object> body = objectMapper.readValue((String) requestEvent.get("body"), Map.class);

		String email = String.valueOf(body.get("email"));
		String password = String.valueOf(body.get("password"));

		logger.log("CreateUser parameters: " + email+" "+password);

		Pattern emailPattern = Pattern.compile(EMAIL_REGEX);

		Matcher emailMatcher = emailPattern.matcher(email);
		if(!emailMatcher.matches()||!validPassword(password)){
			throw new IllegalArgumentException("There was an error in the request.");
		}



		logger.log("Looking up user pool ID for: " + System.getenv("booking_userpool"));
		String userPoolId = System.getenv("COGNITO_ID");
		logger.log("Found user pool ID: " + userPoolId);

		AdminCreateUserRequest adminCreateUserRequest = new AdminCreateUserRequest()
				.withUserPoolId(userPoolId)
				.withUsername(email)
				.withUserAttributes(new AttributeType().withName("email").withValue(email))
				.withMessageAction(MessageActionType.SUPPRESS);
		logger.log("AdminCreateUserRequest: " + adminCreateUserRequest.toString());

		AdminSetUserPasswordRequest adminSetUserPassword = new AdminSetUserPasswordRequest()
				.withPassword(password)
				.withUserPoolId(userPoolId)
				.withUsername(email)
				.withPermanent(true);
		logger.log(adminSetUserPassword.toString());

		logger.log("Creating user in Cognito...");
		cognitoClient.adminCreateUser(adminCreateUserRequest);
		logger.log("User created successfully.");

		logger.log("Setting user password...");
		cognitoClient.adminSetUserPassword(adminSetUserPassword);
		logger.log("Password set successfully.");


		return "{\"statusCode\": 200, \"event\": \"Sign-up process is successful\"}";
	}


//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private String postSignin(Map<String, Object> requestEvent, LambdaLogger logger) throws JsonProcessingException {
		Map<String, Object> body = objectMapper.readValue((String) requestEvent.get("body"), Map.class);

		String email = String.valueOf(body.get("email"));
		String password = String.valueOf(body.get("password"));

		String userPoolId = System.getenv("COGNITO_ID");
		logger.log("Retrieved user pool ID: " + userPoolId);

		String clientId = System.getenv("CLIENT_ID");
		logger.log("Retrieved client ID: " + clientId);

		Map<String, String> authParams = new HashMap<>();
		authParams.put("USERNAME", email);
		authParams.put("PASSWORD", password);
		logger.log("Authentication parameters: " + authParams);

		AdminInitiateAuthRequest authRequest = new AdminInitiateAuthRequest()
				.withAuthFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
				.withUserPoolId(userPoolId)
				.withClientId(clientId)
				.withAuthParameters(authParams);
		logger.log("AdminInitiateAuthRequest: " + authRequest.toString());

		AdminInitiateAuthResult result = cognitoClient.adminInitiateAuth(authRequest);
		logger.log("AdminInitiateAuthResult: " + result.toString());

		String accessToken = "";
		if (result.getAuthenticationResult() != null) {
			accessToken = result.getAuthenticationResult().getIdToken();
			logger.log("Authentication successful. AccessToken: " + accessToken);

			Map<String, Object> jsonResponse = new HashMap<>();
			jsonResponse.put("accessToken", accessToken);

			logger.log("Response JSON: " + jsonResponse);
			return "{\"statusCode\": 200, \"accessToken\": \""+accessToken+"\"}";
		} else {
			logger.log("Authentication failed, no tokens returned.");
			throw new IllegalArgumentException("Authentication failed, no tokens returned.");
		}
	}
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private String getTables() throws JsonProcessingException {


		ScanResult scanResult = getFromDynamoDb(System.getenv("tables_table"));

		List<Map<String, Object>> tableList = new ArrayList<>();
		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			Map<String, Object> table = new LinkedHashMap<>();
			table.put("id", Integer.parseInt(item.get("id").getS()));
			table.put("number", Integer.parseInt(item.get("number").getN()));
			table.put("places", Integer.parseInt(item.get("places").getN()));
			table.put("isVip", Boolean.parseBoolean(item.get("isVip").getBOOL().toString()));
			try{
				table.put("minOrder", Integer.parseInt(item.get("minOrder").getN()));
			}catch (Exception e)
			{}
			tableList.add(table);
		}


		tableList.sort(Comparator.comparing(o -> (Integer) o.get("id")));

		Map<String, Object> jsonResponse = new HashMap<>();
		jsonResponse.put("tables", tableList);
		return objectMapper.writeValueAsString(jsonResponse);
	}
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	private String postTables(Map<String, Object> event, LambdaLogger lambdaLogger) throws JsonProcessingException {
		Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);

		String id = String.valueOf(body.get("id"));
		String number = String.valueOf(body.get("number"));
		String places = String.valueOf(body.get("places"));
		boolean isVip = Boolean.valueOf(body.get("isVip").toString());
		String minOrder = "";

		Map<String, AttributeValue> newTable = new HashMap<>();
		newTable.put("id", new AttributeValue(id));
		newTable.put("number", new AttributeValue().withN(number));
		newTable.put("places", new AttributeValue().withN(places));
		newTable.put("isVip", new AttributeValue().withBOOL(isVip));
		try{
			minOrder = body.get("minOrder").toString();
			newTable.put("minOrder", new AttributeValue().withN(minOrder));
		}catch (Exception e)
		{}

		saveToDynamoDb(newTable, System.getenv("tables_table"));


		return "{\"id\": "+Integer.parseInt(id)+"}";
	}
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private String getTableById(String parseInt) throws JsonProcessingException{

		ScanResult scanResult = getFromDynamoDb(System.getenv("tables_table"));

		Map<String, Object> table = new HashMap<>();
		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			if(item.get("id").getS().equals(parseInt)){
				table.put("id", Integer.parseInt(item.get("id").getS()));
				table.put("number", Integer.parseInt(item.get("number").getN()));
				table.put("places", Integer.parseInt(item.get("places").getN()));
				table.put("isVip", Boolean.parseBoolean(item.get("isVip").getBOOL().toString()));
				try{
					table.put("minOrder", Integer.parseInt(item.get("minOrder").getN()));
				}catch (Exception e)
				{}
				break;
			}
		}

		return objectMapper.writeValueAsString(table);

	}


//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	private String getReservations() throws JsonProcessingException {

		ScanResult scanResult = getFromDynamoDb(System.getenv("reservations_table"));

		List<Map<String, Object>> reservationsList = new ArrayList<>();
		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			Map<String, Object> reservation = new LinkedHashMap<>();
			reservation.put("tableNumber", Integer.parseInt(item.get("tableNumber").getN()));
			reservation.put("clientName", item.get("clientName").getS());
			reservation.put("phoneNumber", item.get("phoneNumber").getS());
			reservation.put("date", item.get("date").getS());
			reservation.put("slotTimeStart", item.get("slotTimeStart").getS());
			reservation.put("slotTimeEnd", item.get("slotTimeEnd").getS());

			reservationsList.add(reservation);
		}


		reservationsList.sort(Comparator.comparing(o -> (Integer) o.get("tableNumber")));

		Map<String, Object> jsonResponse = new HashMap<>();
		jsonResponse.put("reservations", reservationsList);
		return objectMapper.writeValueAsString(jsonResponse);

	}
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	private String postReservations(Map<String, Object> event, LambdaLogger lambdaLogger) throws JsonProcessingException {
		Map<String, Object> body = objectMapper.readValue((String) event.get("body"), Map.class);

		String id = UUID.randomUUID().toString();
		String tableNumber = String.valueOf(body.get("tableNumber"));
		String clientName = String.valueOf(body.get("clientName"));
		String phoneNumber = String.valueOf(body.get("phoneNumber"));
		String date = String.valueOf(body.get("date"));
		String slotTimeStart = String.valueOf(body.get("slotTimeStart"));
		String slotTimeEnd = String.valueOf(body.get("slotTimeEnd"));

		Map<String, AttributeValue> newReservation = new HashMap<>();
		newReservation.put("id", new AttributeValue(id));
		newReservation.put("tableNumber", new AttributeValue().withN(tableNumber));
		newReservation.put("clientName", new AttributeValue(clientName));
		newReservation.put("phoneNumber", new AttributeValue(phoneNumber));
		newReservation.put("date", new AttributeValue(date));
		newReservation.put("slotTimeStart", new AttributeValue(slotTimeStart));
		newReservation.put("slotTimeEnd", new AttributeValue(slotTimeEnd));

		if (!doesTableExist(tableNumber, lambdaLogger)) {
			lambdaLogger.log("Table does not exist");
			throw new IllegalArgumentException("Table does not exist");
		}

		if (isReservationOverlapping(tableNumber, date, slotTimeStart, slotTimeEnd)) {
			lambdaLogger.log("Table does not exist");
			throw new IllegalArgumentException("Reservation overlaps with an existing reservation");
		}

		saveToDynamoDb(newReservation, System.getenv("reservations_table"));


		return "{\"reservationId\": \""+id+"\"}";
	}
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	private void saveToDynamoDb(Map<String, AttributeValue> itemValues, String table) {
		final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();
		ddb.putItem(table, itemValues);
	}

	private ScanResult getFromDynamoDb(String table) {
		final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();

		ScanRequest scanRequest = new ScanRequest().withTableName(table);
		ScanResult scanResult = ddb.scan(scanRequest);
		return scanResult;
	}

	private APIGatewayV2HTTPResponse buildResponse(int statusCode, String body) {
		return APIGatewayV2HTTPResponse.builder()
				.withStatusCode(statusCode)
				.withHeaders(responseHeaders)
				.withBody(body)
				.build();
	}

	public static boolean validPassword(String password) {
		if (password == null) {
			return false;
		}

		return password.length() >= 8 &&
				password.length() <= 20 &&
				password.matches(".*[A-Z].*") &&
				password.matches(".*[a-z].*") &&
				password.matches(".*\\d.*") &&
				password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*");
	}

	public boolean doesTableExist(String tableNumber, LambdaLogger logger) {
		AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();
		ScanResult scanResult = ddb.scan(new ScanRequest().withTableName(System.getenv("tables_table")));

		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			if (tableNumber.equals(item.get("number").getN())) {
				logger.log("Table exists, number: " + tableNumber);
				return true;
			}
		}
		return false;
	}

	public boolean isReservationOverlapping(String tableNumber, String date, String slotTimeStart, String slotTimeEnd) {
		AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
				.withRegion(System.getenv("region"))
				.build();
		ScanResult scanResult = ddb.scan(new ScanRequest().withTableName(System.getenv("reservations_table")));
		for (Map<String, AttributeValue> item : scanResult.getItems()) {
			String existingTableNumber = item.get("tableNumber").getS();
			String existingDate = item.get("date").getS();

			if (tableNumber.equals(existingTableNumber) && date.equals(existingDate)) {
				String existingSlotTimeStart = item.get("slotTimeStart").getS();
				String existingSlotTimeEnd = item.get("slotTimeEnd").getS();

				return timeOverlap(slotTimeStart, slotTimeEnd, existingSlotTimeStart, existingSlotTimeEnd);
			}
		}

		return false;
	}
	private boolean timeOverlap(String slotTimeStart, String slotTimeEnd, String existingSlotTimeStart, String existingSlotTimeEnd) {

		LocalTime start = LocalTime.parse(slotTimeStart);
		LocalTime end = LocalTime.parse(slotTimeEnd);
		LocalTime existingStart = LocalTime.parse(existingSlotTimeStart);
		LocalTime existingEnd = LocalTime.parse(existingSlotTimeEnd);

		return (start.isBefore(existingEnd) && end.isAfter(existingStart));
	}

}
