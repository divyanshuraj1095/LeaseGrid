import { configure, prepare, exercise } from './suite.js';

export const options = configure('concurrency');
export const setup = prepare;
export default function (fixture) {
    exercise('concurrency', fixture);
}
