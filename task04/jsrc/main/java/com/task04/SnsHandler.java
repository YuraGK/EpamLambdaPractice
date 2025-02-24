package com.task04;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

import java.util.HashMap;
import java.util.Map;

@LambdaHandler(
    lambdaName = "sns_handler",
	roleName = "sns_handler-role",
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
public class SnsHandler implements RequestHandler<Map<String, Object>, String> {

	public String handleRequest(Map<String, Object> snsEvent, Context context) {
		// Log the SNS event (full message) to CloudWatch Logs
		System.out.println("Received SNS Message: " + snsEvent);

		// Log the Message content specifically (assuming it's in the "Message" field of the SNS event)
		String snsMessage = (String) snsEvent.get("Message");
		System.out.println("Message content: " + snsMessage);

		// Optional: Return a response (if required by your architecture)
		return "SNS message processed successfully!";
	}
}
