package com.task11;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.*;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

	private final Map<String, String> responseHeaders = Map.of("Content-Type", "application/json");
	private final ObjectMapper objectMapper = new ObjectMapper();
	private static final String EMAIL_REGEX = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
	private static final String PASSWORD_REGEX = "^(?=.*[A-Za-z0-9])(?=.*[$%^*\\-_])[A-Za-z0-9$%^*\\-_]{12,}$";
	private final AWSCognitoIdentityProvider cognitoClient = AWSCognitoIdentityProviderClientBuilder.defaultClient();
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
		LambdaLogger lambdaLogger = context.getLogger();
		String resultBody = "{\"statusCode\": 200, \"event\": o}";

		String method = event.getRequestContext().getHttp().getMethod();
		String rawPath = event.getRawPath();

		try {
			if ("POST".equals(method) && "/signup".equals(rawPath)) {
				resultBody = postSignup(event);
			} else if ("POST".equals(method) && "/signin".equals(rawPath)) {
				resultBody = postSignin(event);
			}else if ("GET".equals(method) && "/tables".equals(rawPath)) {
				resultBody = "{\"statusCode\": 200, \"event\": \"l\"}";
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

	private String postSignin(APIGatewayV2HTTPEvent requestEvent) throws JsonProcessingException {
		JsonNode jsonNode = objectMapper.readTree(requestEvent.getBody());

		String email = jsonNode.get("email").asText();
		String password = jsonNode.get("password").asText();
		Pattern emailPattern = Pattern.compile(EMAIL_REGEX);
		Matcher emailMatcher = emailPattern.matcher(email);
		Pattern passwordPattern = Pattern.compile(PASSWORD_REGEX);
		Matcher passwordMatcher = passwordPattern.matcher(password);
		if(!emailMatcher.matches()||!passwordMatcher.matches()){
			throw new IllegalArgumentException("There was an error in the request.");
		}

		String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("No such user pool"));

		String clientId = getClientIdByUserPoolName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("No such client ID"));

		Map<String, String> authParams = new HashMap<>();
		authParams.put("USERNAME", email);
		authParams.put("PASSWORD", password);

		AdminInitiateAuthRequest authRequest = new AdminInitiateAuthRequest()
				.withAuthFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
				.withUserPoolId(userPoolId)
				.withClientId(clientId)
				.withAuthParameters(authParams);

		AdminInitiateAuthResult result = cognitoClient.adminInitiateAuth(authRequest);

		if (result.getAuthenticationResult() != null) {
			String accessToken = result.getAuthenticationResult().getIdToken();

			Map<String, Object> jsonResponse = new HashMap<>();
			jsonResponse.put("accessToken", accessToken);

		} else {
			throw new IllegalArgumentException("There was an error in the request.");
		}

		String accessToken = "";
		return "{\"statusCode\": 200, \"event\": \""+accessToken+"\"}";
	}

	private String postSignup(APIGatewayV2HTTPEvent requestEvent) throws JsonProcessingException {
		JsonNode jsonNode = objectMapper.readTree(requestEvent.getBody());

		String firstName = jsonNode.get("firstName").asText();
		String lastName = jsonNode.get("lastName").asText();
		String email = jsonNode.get("email").asText();
		String password = jsonNode.get("password").asText();

		Pattern emailPattern = Pattern.compile(EMAIL_REGEX);
		Matcher emailMatcher = emailPattern.matcher(email);
		Pattern passwordPattern = Pattern.compile(PASSWORD_REGEX);
		Matcher passwordMatcher = passwordPattern.matcher(password);
		if(!emailMatcher.matches()||!passwordMatcher.matches()){
			throw new IllegalArgumentException("There was an error in the request.");
		}

		String userPoolId = getUserPoolIdByName(System.getenv("booking_userpool"))
				.orElseThrow(() -> new IllegalArgumentException("No such user pool"));

		AdminCreateUserRequest adminCreateUserRequest = new AdminCreateUserRequest()
				.withUserPoolId(userPoolId)
				.withUsername(email)
				.withUserAttributes(new AttributeType().withName("email").withValue(email))
				.withMessageAction(MessageActionType.SUPPRESS);

		AdminSetUserPasswordRequest adminSetUserPassword = new AdminSetUserPasswordRequest()
				.withPassword(password)
				.withUserPoolId(userPoolId)
				.withUsername(email)
				.withPermanent(true);

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

	private APIGatewayV2HTTPResponse buildResponse(int statusCode, String body) {
		return APIGatewayV2HTTPResponse.builder()
				.withStatusCode(statusCode)
				.withHeaders(responseHeaders)
				.withBody(body)
				.build();
	}
}
