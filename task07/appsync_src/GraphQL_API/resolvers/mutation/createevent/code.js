import * as ddb from '@aws-appsync/utils/dynamodb';
import { util } from '@aws-appsync/utils';

export function request(ctx) {
    return {
        "version": "2018-05-29",
        "operation": "PutItem",
        "key": {
            "id": ddb.toDynamoDBJson(util.autoId())
        },
        "attributeValues": {
            "userId": ddb.toDynamoDBJson(ctx.args.userId),
            "createdAt": ddb.toDynamoDBJson(util.time.nowISO8601()),
            "payLoad": ddb.toDynamoDBJson(ctx.args.payLoad)
        }
    };
}

export function response(ctx) {
    return util.toJson({
        "id": ctx.result.id,
        "createdAt": ctx.result.createdAt
    });
}