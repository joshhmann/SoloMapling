package soloMapling.ArtificialPlayer;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * How full the world is at a given local time (bot-persistence ticket 08): a daily curve of fractions of the
 * peak, linearly interpolated between hour points and wrapping past midnight, lifted on weekends.
 * Written in config as {@code "0:0.45,6:0.15,20:1.0"} (hour:fraction, any order), or {@code "flat"} for a
 * constant full peak (a test mode).
 */
public final class BotPopulationCurve {

    /** A busy evening, a quiet early morning: roughly a private server's day. */
    public static final String DEFAULT = "0:0.45,3:0.25,6:0.15,9:0.25,12:0.45,15:0.6,18:0.85,20:1.0,22:0.8";

    private final double[] hours;
    private final double[] fractions;
    private final double weekendLift;

    private BotPopulationCurve(double[] hours, double[] fractions, double weekendLift) {
        this.hours = hours;
        this.fractions = fractions;
        this.weekendLift = weekendLift;
    }

    public static BotPopulationCurve parse(String spec, double weekendLift) {
        if (spec == null || spec.isBlank() || spec.trim().equalsIgnoreCase("flat")) {
            return new BotPopulationCurve(new double[]{0}, new double[]{1.0}, 1.0);
        }
        List<double[]> points = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] hf = part.trim().split(":");
            if (hf.length != 2) {
                throw new IllegalArgumentException("Bad curve point '" + part + "' in '" + spec + "'");
            }
            double hour = Double.parseDouble(hf[0].trim());
            double fraction = Double.parseDouble(hf[1].trim());
            if (hour < 0 || hour >= 24 || fraction < 0) {
                throw new IllegalArgumentException("Curve point out of range: '" + part + "'");
            }
            points.add(new double[]{hour, fraction});
        }
        points.sort((a, b) -> Double.compare(a[0], b[0]));
        double[] hours = new double[points.size()];
        double[] fractions = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            hours[i] = points.get(i)[0];
            fractions[i] = points.get(i)[1];
        }
        return new BotPopulationCurve(hours, fractions, weekendLift <= 0 ? 1.0 : weekendLift);
    }

    /** The fraction of peak at this hour of the day (0 <= hour < 24), before the weekend lift. */
    public double fractionAt(double hour) {
        int n = hours.length;
        if (n == 1) {
            return fractions[0];
        }
        // The point at or before this hour, wrapping to the last point of the previous day.
        int before = n - 1;
        for (int i = 0; i < n; i++) {
            if (hours[i] <= hour) {
                before = i;
            }
        }
        int after = (before + 1) % n;
        double from = hours[before];
        double to = hours[after];
        double span = (to - from + 24) % 24;
        if (span == 0) {
            return fractions[before];
        }
        double into = (hour - from + 24) % 24;
        return fractions[before] + (fractions[after] - fractions[before]) * (into / span);
    }

    /** How many bots should be online now. */
    public int target(int peak, ZonedDateTime now) {
        double hour = now.getHour() + now.getMinute() / 60.0;
        double fraction = fractionAt(hour);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            fraction *= weekendLift;
        }
        return (int) Math.round(Math.max(0, Math.min(1.0, fraction)) * peak);
    }
}
