/** An error that carries its HTTP status; Fastify turns it into `{ statusCode, error, message }`. */
export class HttpError extends Error {
  constructor(public statusCode: number, message: string) { super(message); }
}

export const badRequest = (message: string) => new HttpError(400, message);
export const unauthorized = (message = 'Unauthorized') => new HttpError(401, message);
export const forbidden = (message = 'Forbidden') => new HttpError(403, message);
export const notFound = (message = 'Not found') => new HttpError(404, message);
export const conflict = (message: string) => new HttpError(409, message);
