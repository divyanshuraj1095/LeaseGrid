import { configure, prepare, exercise } from './suite.js';

export const options = configure('idempotency');
export const setup = prepare;
export default function (fixture) {
    exercise('idempotency', fixture);
}
