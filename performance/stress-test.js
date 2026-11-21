import { configure, prepare, exercise } from './suite.js';

export const options = configure('stress');
export const setup = prepare;
export default function (fixture) {
    exercise('stress', fixture);
}
