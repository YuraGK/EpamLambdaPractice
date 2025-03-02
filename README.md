# EpamLambdaPractice

{
"version": "2018-05-29",
"operation": "PutItem",
"key": {
"id": $util.dynamodb.toDynamoDBJson($util.autoId())
},
"attributeValues": {
"userId": $util.dynamodb.toDynamoDBJson($ctx.args.userId),
"createdAt": $util.dynamodb.toDynamoDBJson($util.time.nowISO8601()),
"payLoad": $util.dynamodb.toDynamoDBJson($ctx.args.payLoad)
}
}

