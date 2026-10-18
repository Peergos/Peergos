package peergos.shared.routing;

public enum Outcome {
    /** The record changed the stored state. */
    Added,
    /** Already held. */
    Duplicate,
    /** Valid, but superseded or beyond the evidence bounds, so not kept. */
    Ignored,
    /** Depends on records not held yet; offer it again once they arrive. */
    Unconnected,
    /** Invalid against the records held. */
    Rejected,
    /** Valid and new, but over a rate limit; it stays with whoever offered it, to be offered again. */
    Limited
}
