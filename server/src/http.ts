/** The fetch signature we use, so tests can hand in a fake. */
export type Fetch = (input: string, init?: RequestInit) => Promise<Response>;
