package dev.briefestboxer.core;

/**
 * Where the player wants a Sulfur Cube to come to rest: a point with a tolerance
 * radius, or a box. Set from a client command; the overlay then marks the spots
 * on the cube that send it there. Session-only: it is not saved with the configuration.
 */
public final class AimDestination {
    /** Default tolerance around a destination point, in blocks. */
    public static final double DEFAULT_RADIUS = 1.5;
    public static final double MIN_RADIUS = 0.25;
    public static final double MAX_RADIUS = 32.0;
    /** Largest box edge accepted, in blocks. */
    public static final double MAX_AREA_SIZE = 256.0;

    /**
     * A destination for the cube's bottom centre at rest: a sphere (point plus
     * radius) or an axis-aligned box. {@code x, y, z} is the centre of either.
     */
    public static final class Target {
        public final double x, y, z, radius;
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        public final boolean box;

        private Target(double x, double y, double z, double radius,
                       double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean box) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.box = box;
        }

        /** A point destination with a tolerance radius (clamped to the allowed range). */
        public Target(double x, double y, double z, double radius) {
            this(requireFinite(x), requireFinite(y), requireFinite(z), clampRadius(radius),
                    x - clampRadius(radius), y - clampRadius(radius), z - clampRadius(radius),
                    x + clampRadius(radius), y + clampRadius(radius), z + clampRadius(radius), false);
        }

        /** A box between two corners given in any order. */
        public static Target box(double x1, double y1, double z1, double x2, double y2, double z2) {
            double minX = Math.min(requireFinite(x1), requireFinite(x2));
            double minY = Math.min(requireFinite(y1), requireFinite(y2));
            double minZ = Math.min(requireFinite(z1), requireFinite(z2));
            double maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
            if (maxX - minX > MAX_AREA_SIZE || maxY - minY > MAX_AREA_SIZE || maxZ - minZ > MAX_AREA_SIZE) {
                throw new IllegalArgumentException("area is larger than " + (int) MAX_AREA_SIZE + " blocks");
            }
            return new Target((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5, 0.0,
                    minX, minY, minZ, maxX, maxY, maxZ, true);
        }

        /**
         * A box covering whole blocks between two block positions, inclusive (as
         * {@code /fill} does): a cube resting on the floor of any of those blocks counts.
         */
        public static Target blockArea(int x1, int y1, int z1, int x2, int y2, int z2) {
            return box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);
        }

        /** Whether a resting cube whose bottom centre is at the given point counts as arrived. */
        public boolean contains(double px, double py, double pz) {
            return distance(px, py, pz) <= 0.0;
        }

        /** How far the point is from the destination; zero inside it. */
        public double distance(double px, double py, double pz) {
            if (!box) {
                double dx = px - x, dy = py - y, dz = pz - z;
                return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
            }
            double dx = Math.max(0.0, Math.max(minX - px, px - maxX));
            double dy = Math.max(0.0, Math.max(minY - py, py - maxY));
            double dz = Math.max(0.0, Math.max(minZ - pz, pz - maxZ));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** A rough size of the destination, for scaling search effort. */
        public double tolerance() {
            if (!box) return radius;
            return Math.max(MIN_RADIUS, 0.5 * Math.min(maxX - minX, maxZ - minZ));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Target)) return false;
            Target t = (Target) other;
            return t.box == box && t.x == x && t.y == y && t.z == z && t.radius == radius
                    && t.minX == minX && t.minY == minY && t.minZ == minZ
                    && t.maxX == maxX && t.maxY == maxY && t.maxZ == maxZ;
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(box, x, y, z, radius, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static volatile Target target;

    private AimDestination() {}

    public static Target current() {
        return target;
    }

    public static Target set(double x, double y, double z, double radius) {
        return set(new Target(x, y, z, radius));
    }

    public static Target set(Target next) {
        if (next == null) throw new IllegalArgumentException("target is required");
        target = next;
        return next;
    }

    public static void clear() {
        target = null;
    }

    public static double clampRadius(double radius) {
        if (!Double.isFinite(radius)) return DEFAULT_RADIUS;
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    private static double requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("destination must be finite");
        return value;
    }
}
