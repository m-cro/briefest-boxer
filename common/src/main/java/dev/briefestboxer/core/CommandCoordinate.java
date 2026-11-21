package dev.briefestboxer.core;

/**
 * One command coordinate: absolute ({@code 12}, {@code -3.5}) or relative to the
 * player ({@code ~}, {@code ~2}, {@code ~-0.5}), resolved the way vanilla
 * commands resolve them.
 */
public final class CommandCoordinate {
    public final boolean relative;
    public final double value;
    /** Written without a decimal point, e.g. {@code 12} rather than {@code 12.0}. */
    public final boolean wholeNumber;

    public CommandCoordinate(boolean relative, double value, boolean wholeNumber) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("coordinate must be finite");
        this.relative = relative;
        this.value = value;
        this.wholeNumber = wholeNumber;
    }

    /**
     * Parses {@code ~}, {@code ~<number>} or {@code <number>}.
     *
     * @throws IllegalArgumentException if the text is not a coordinate
     */
    public static CommandCoordinate parse(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("expected a coordinate");
        boolean relative = text.charAt(0) == '~';
        String number = relative ? text.substring(1) : text;
        if (relative && number.isEmpty()) return new CommandCoordinate(true, 0.0, true);
        if (!number.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) {
            throw new IllegalArgumentException("invalid coordinate: " + text);
        }
        return new CommandCoordinate(relative, Double.parseDouble(number), !number.contains("."));
    }

    /**
     * A point coordinate. Relative ones offset the player's exact position; as in
     * vanilla, a whole-number absolute x or z ({@code centre}) means the block centre.
     */
    public double resolvePoint(double origin, boolean centre) {
        if (relative) return origin + value;
        return centre && wholeNumber ? value + 0.5 : value;
    }

    /** A block coordinate: the block containing the resolved position. */
    public int resolveBlock(double origin) {
        return (int) Math.floor(relative ? origin + value : value);
    }

    @Override public String toString() {
        return relative ? "~" + (value == 0.0 ? "" : value) : Double.toString(value);
    }
}
