import { util } from '@aws-appsync/utils';
export function request(ctx) {

    const items = ctx.prev.result.items;

    if (!items || items.length === 0) {
        return util.error("NotFoundError");
    }

    return {
            operation: "PutItem",
            key: util.dynamodb.toMapValues({
                PK: ctx.args.input.userId
            }),
            attributeValues: util.dynamodb.toMapValues({
                userId: ctx.args.input.userId,
                payLoad: ctx.args.input.payLoad,
                nic: ctx.args.input.nic,
                personal_info: ctx.args.input.personal_info
            }),
            condition: {
            }
        };
}

/**
 * Returns the resolver result
 * @param {import('@aws-appsync/utils').Context} ctx the context
 * @returns {*} the result
 */
export function response(ctx) {
    if (ctx.error) {
        return util.error(ctx.error.message, ctx.error.type);
    }
    return ctx.result;
}
