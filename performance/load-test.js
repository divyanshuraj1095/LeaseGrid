import { configure, prepare, exercise } from './suite.js';

export const options = configure('load');
export const setup = prepare;
export default function (fixture) {
    exercise('load', fixture);
}
