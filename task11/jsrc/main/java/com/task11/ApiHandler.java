package com.task11;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.*;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.services.dynamodbv2.model.AttributeValue;
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
		@EnvironmentVariable(key = "booking_userpool", value = "${booking_userpool}")
}
)
public class ApiHandler implements RequestHandler<Map<String, Object>, APIGatewayV2HTTPResponse> {

	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");
	private final ObjectMapper objectMapper = new ObjectMapper();
	private static final String EMAIL_REGEX = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
	private static final String PASSWORD_REGEX = "^(?=.*[A-Za-z0-9])(?=.*[$%^*\\-_])[A-Za-z0-9$%^*\\-_]{12,}$";
	private final AWSCognitoIdentityProvider cognitoClient = AWSCognitoIdentityProviderClientBuilder.defaultClient();
	public APIGatewayV2HTTPResponse handleRequest(Map<String, Object> event, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		String resultBody = "{\"statusCode\": 200, \"event\": o}";

		String rawPath = (String) event.get("path");
		String method = (String) event.get("httpMethod");

		try {
			if ("POST".equals(method) && "/signup".equals(rawPath)) {
				resultBody = postSignup(event, lambdaLogger);
			} else if ("POST".equals(method) && "/signin".equals(rawPath)) {
				resultBody = postSignin(event, lambdaLogger);
			}else if ("GET".equals(method) && "/tables".equals(rawPath)) {
				resultBody = getTables();
			}else if ("POST".equals(method) && "/tables".equals(rawPath)) {
				resultBody = "{\"statusCode\": 200, \"event\": \"l\"}";
			}else if ("GET".equals(method) && "/tables/".equals(rawPath.substring(0, rawPath.length() - 1))) {
				resultBody = "{\"statusCode\": 200, \"event\": \"l\"}";
			}else if ("POST".equals(method) && "/reservations".equals(rawPath)) {
				resultBody = "{\"statusCode\": 200, \"event\": \"l\"}";
			}else if ("GET".equals(method) && "/reservations".equals(rawPath)) {
				resultBody = "{\"statusCode\": 200, \"event\": \"l\"}";
			}


		}catch (Exception e) {
			e.printStackTrace();
			lambdaLogger.log("ERROR"+e.getMessage());
			return buildResponse(400, "{\"statusCode\": 400, \"event\": "+ e.getMessage() +"}");
		}

		return buildResponse(200, resultBody);
	}

	private String getTables() {


		ScanResult scanResult = getFromDynamoDb(System.getenv("tables_table"));

		List<AttributeValue> tableList = new ArrayList<>();
		////


		Map<String, AttributeValue> itemValues = new HashMap<>();
		itemValues.put("tables",new AttributeValue().withL(tableList));
		AttributeValue resBody = new AttributeValue().withM(itemValues);

		return "{\"statusCode\": 200, \"event\": \""+resBody+"\"}";
	}

	private String postSignin(Map<String, Object> requestEvent, LambdaLogger logger) throws JsonProcessingException {
		Map<String, Object> body = objectMapper.readValue((String) requestEvent.get("body"), Map.class);

		String email = String.valueOf(body.get("email"));
		String password = String.valueOf(body.get("password"));
		Pattern emailPattern = Pattern.compile(EMAIL_REGEX);
		Matcher emailMatcher = emailPattern.matcher(email);
		Pattern passwordPattern = Pattern.compile(PASSWORD_REGEX);
		Matcher passwordMatcher = passwordPattern.matcher(password);
		if(!emailMatcher.matches()||!passwordMatcher.matches()){
			throw new IllegalArgumentException("There was an error in the request.");
		}

		String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("No such user pool"));
		logger.log("Retrieved user pool ID: " + userPoolId);

		String clientId = getClientIdByUserPoolName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("No such client ID"));
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
			return "{\"statusCode\": 200, \"event\": \""+accessToken+"\"}";
		} else {
			logger.log("Authentication failed, no tokens returned.");
			throw new IllegalArgumentException("Authentication failed, no tokens returned.");
		}
	}

	private String postSignup(Map<String, Object> requestEvent, LambdaLogger logger) throws JsonProcessingException {
		Map<String, Object> body = objectMapper.readValue((String) requestEvent.get("body"), Map.class);

		String email = String.valueOf(body.get("email"));
		String password = String.valueOf(body.get("password"));

		Pattern emailPattern = Pattern.compile(EMAIL_REGEX);
		Matcher emailMatcher = emailPattern.matcher(email);
		Pattern passwordPattern = Pattern.compile(PASSWORD_REGEX);
		Matcher passwordMatcher = passwordPattern.matcher(password);
		if(!emailMatcher.matches()||!passwordMatcher.matches()){
			throw new IllegalArgumentException("There was an error in the request.");
		}

		logger.log("Looking up user pool ID for: " + System.getenv("booking_userpool"));
		String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("No such user pool"));
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

		cognitoClient.adminCreateUser(adminCreateUserRequest);
		cognitoClient.adminSetUserPassword(adminSetUserPassword);

		return "{\"statusCode\": 200, \"event\": \"Sign-up process is successful\"}";
	}

	public Optional<String> getUserPoolIdByName(String userPoolName) {
		String nextToken = null;

		do {
			ListUserPoolsRequest listUserPoolsRequest = new ListUserPoolsRequest()
					.withMaxResults(60)
					.withNextToken(nextToken);

			ListUserPoolsResult listUserPoolsResult = cognitoClient.listUserPools(listUserPoolsRequest);

			for (UserPoolDescriptionType pool : listUserPoolsResult.getUserPools()) {
				if (pool.getName().equals(userPoolName)) {
					return Optional.of(pool.getId());
				}
			}

			nextToken = listUserPoolsResult.getNextToken();
		} while (nextToken != null);

		return Optional.empty();
	}

	public Optional<String> getClientIdByUserPoolName(String userPoolName) {
		String userPoolId = getUserPoolIdByName(userPoolName).get();

		ListUserPoolClientsRequest listUserPoolClientsRequest = new ListUserPoolClientsRequest().withUserPoolId(userPoolId);
		ListUserPoolClientsResult listUserPoolClientsResult = cognitoClient.listUserPoolClients(listUserPoolClientsRequest);

		for (UserPoolClientDescription client : listUserPoolClientsResult.getUserPoolClients()) {
			return Optional.of(client.getClientId());
		}

		return Optional.empty();
	}

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
}
