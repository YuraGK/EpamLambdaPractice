import { util } from '@aws-appsync/utils';
export function request(ctx) {

    const items = ctx.prev.result.items;

    if (!items || items.length === 0) {
        return util.error("NotFoundError");
    }

    const currentDate = new Date();
    const isoString = currentDate.toISOString();
    console.log(isoString);
    const id = ctx.stash.id;

    const parsedObject = JSON.parse(ctx.args.input.payLoad);
    const key1 = parsedObject.meta.key1;
    const key2 = parsedObject.meta.key2;

    return {
            operation: "PutItem",
            key: util.dynamodb.toMapValues({
                PK: ctx.args.input.userId
            }),
            attributeValues: util.dynamodb.toMapValues({
                id: id,
                userId: ctx.args.input.userId,
                createdAt: isoString,
                payLoad:
                    meta:
                        key1: key1,
                        key2: key2
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
