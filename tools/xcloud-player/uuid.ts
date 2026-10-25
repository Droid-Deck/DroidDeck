// Stand-in for the "uuid" package (only v4 is used by the Greenlight player).
export function v4(): string { return crypto.randomUUID() }
