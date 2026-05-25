package pez.mini;

import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;
import java.util.ArrayList;

//This code is released under the RoboWiki Public Code Licence (RWPCL), datailed on:
//http://robowiki.net/?RWPCL
//(Basically it means you must keep the code public if you base any bot on it.)

//Pugilist, by PEZ. Although a pugilist needs strong and accurate fists, he/she even more needs an evasive movement.

//Pugilist explores two major concepts:
//1. Guess factor targeting, invented by Paul Evans. http://robowiki.net/?GuessFacorTargeting
//2. Wave surfing movement, invented by ABC. http://robowiki.net/?WaveSurfing

//Many thanks to Jim, Kawigi, iiley, Jamougha, Axe, ABC, rozu, Kuuran, FnH, nano and many others who have helped me.
//Check out http://robowiki.net/?Members to get an idea about who those people are. =)

public class Pugilist extends AdvancedRobot {
    static final double MAX_VELOCITY = 8;
    static final double BATTLE_FIELD_WIDTH = 800;
    static final double BATTLE_FIELD_HEIGHT = 600;
    static final double WALL_MARGIN = 20;
    static final double MAX_BULLET_POWER = 3.0;
    static final double BULLET_POWER = 1.9;

    static Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
            BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
    static Point2D robotLocation = new Point2D.Double();
    static Point2D enemyLocation = new Point2D.Double();
    static double enemyDistance;
    static int distanceIndex;
    static int velocityIndex;
    static double enemyVelocity;
    static double enemyEnergy;
    static int enemyTimeSinceVChange;
    static double enemyBearingDirection;

    static double enemyFirePower = MAX_BULLET_POWER;
    static double robotVelocity;
    static Pugilist robot;

    public void run() {
        setAdjustRadarForGunTurn(true);
        setAdjustGunForRobotTurn(true);
        robot = this;
        W.passingWave = null;
        turnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        W wave = new W();
        W ew = new W();
        ew.gunLocation = project(enemyLocation, 0, 0);
        ew.startBearing = ew.gunBearing(robotLocation);

        double enemyDeltaEnergy = enemyEnergy - (enemyEnergy = e.getEnergy());
        if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= 3.0) {
            enemyFirePower = enemyDeltaEnergy;
            ew.surfable = true;
        }

        double direction = robotBearingDirection(ew.startBearing);
        ew.initObs(enemyFirePower, direction);
        int accelIndex = 1;
        if (robotVelocity != getVelocity()) {
            accelIndex = sign(robotVelocity - getVelocity()) + 1;
        }
        ew.obs = new double[] { 0,
            distanceIndex = (int)Math.min(W.DISTANCE_INDEXES - 1, enemyDistance / 180),
            (int)Math.abs(robotVelocity / 2), accelIndex, 0, 0, 0 };
        robotVelocity = getVelocity();
        ew.targetLocation = robotLocation;

        robotLocation.setLocation(getX(), getY());

        double enemyAbsoluteBearing;
        wave.startBearing = enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
        enemyLocation.setLocation(
            project(wave.gunLocation = project(robotLocation, 0, 0), enemyAbsoluteBearing, enemyDistance));
        wave.targetLocation = enemyLocation;
        enemyDistance = e.getDistance();

        ew.advance(2);
        addCustomEvent(ew);

        // <gun>
        if (enemyVelocity != (enemyVelocity = e.getVelocity())) {
            enemyTimeSinceVChange = 0;
        }

        double bulletPower = Math.min(enemyEnergy / 4,
            distanceIndex > 0 ? BULLET_POWER : MAX_BULLET_POWER);

        if (enemyVelocity != 0) {
            enemyBearingDirection = sign(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        wave.initObs(bulletPower, enemyBearingDirection);
        int currentVelocityIndex = (int)Math.abs(enemyVelocity / 2);
        wave.obs = new double[] { 0, distanceIndex, velocityIndex, currentVelocityIndex,
            (int)Math.clamp((long)(Math.pow(enemyTimeSinceVChange++, 0.45) - 1),
                0, W.VCHANGE_TIME_INDEXES - 1), wallIndex(wave), 0 };
        velocityIndex = currentVelocityIndex;

        wave.query(W.gunObss);
        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
            wave.bearingDirection * (W.bestGF() - W.MIDDLE_FACTOR)));

        addCustomEvent(wave);
        if (getEnergy() >= BULLET_POWER && Math.abs(getGunTurnRemainingRadians()) < Math.atan2(18, enemyDistance)) {
            setFire(bulletPower);
        }
        // </gun>

        if (W.dangerReverse < W.dangerForward) {
            direction = -direction;
        }
        double angle;
        setAhead(Math
                .cos(angle = wave.gunBearing(wallSmoothedDestination(robotLocation, direction)) - getHeadingRadians())
                * 100);
        setTurnRightRadians(Math.tan(angle));

        setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
        W.dangerForward = W.dangerReverse = 0;
    }

    public void onHitByBullet(HitByBulletEvent e) {
        W.passingWave.record(W.surfObss);
    }

    static int wallIndex(W wave) {
        int wallIndex = 0;
        do {
            wallIndex++;
        } while (wallIndex < W.WALL_INDEXES &&
                fieldRectangle.contains(project(wave.gunLocation,
                        wave.startBearing + wave.bearingDirection * (wallIndex * 5.5), enemyDistance)));
        return wallIndex - 1;
    }

    // Surf: compute danger for forward/reverse using pre-smoothed scores
    void updateDirectionStats(Condition condition) {
        W wave = (W) condition;
        wave.query(W.surfObss);
        double d = Math.abs(wave.distanceFromTarget(wave.targetLocation, 0)) * wave.bulletVelocity;
        W.dangerForward += W.scores[wave.visitingIndex(waveImpactLocation(wave, 1.0, 0))] / d;
        W.dangerReverse += W.scores[wave.visitingIndex(waveImpactLocation(wave, -1.0, 5))] / d;
    }

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        Point2D destination = new Point2D.Double();
        for (;;) {
            double currentSmoothing = 0;
            while (currentSmoothing < 100 && !fieldRectangle.contains(destination = project(location,
                            absoluteBearing(location, enemyLocation) - direction *
                            (Math.PI / 2 + 0.25 - (currentSmoothing++ / 100.0)),
                            Math.clamp(enemyDistance / 1.7, 40.0, 150.0))))
                ;
            if (currentSmoothing < 45 || direction == 0)
                break;
            direction = 0;
        }
        return destination;
    }

    static double wallSmooth(Point2D from, Point2D toward, double direction) {
        double w = 0;
        while (w < 100 && !fieldRectangle.contains(project(from, absoluteBearing(from, toward)
                - direction * (Math.PI / 2 + 0.25 - (w++ / 100.0)), Math.clamp(enemyDistance / 1.7, 40.0, 150.0))))
            ;
        return w;
    }

    Point2D waveImpactLocation(W wave, double direction, int timeOffset) {
        Point2D impactLocation = project(robotLocation, 0, 0);
        do {
            impactLocation = project(impactLocation, absoluteBearing(impactLocation,
                    wallSmoothedDestination(impactLocation,
                            direction * robotBearingDirection(wave.gunBearing(robotLocation)))),
                    MAX_VELOCITY);
            timeOffset++;
        } while (wave.distanceFromTarget(impactLocation, timeOffset) > -10);
        return impactLocation;
    }

    double robotBearingDirection(double enemyBearing) {
        return sign(getVelocity() * Math.sin(getHeadingRadians() - enemyBearing));
    }

    static Point2D project(Point2D sourceLocation, double angle, double length) {
        return new Point2D.Double(sourceLocation.getX() + Math.sin(angle) * length,
                sourceLocation.getY() + Math.cos(angle) * length);
    }

    static double absoluteBearing(Point2D source, Point2D target) {
        return Math.atan2(target.getX() - source.getX(), target.getY() - source.getY());
    }

    static int sign(double v) {
        return v < 0 ? -1 : 1;
    }
}

class W extends Condition {
    static final int DISTANCE_INDEXES = 5;
    static final int WALL_INDEXES = 4;
    static final int VCHANGE_TIME_INDEXES = 6;
    static final int FACTORS = 31;
    static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
    static final int DIM_GF = 0, DIM_DIST = 1, DIM_2 = 2, DIM_3 = 3,
        DIM_4 = 4, DIM_5 = 5, DIM_6 = 6, NUM_DIMS = 7;
    static final String GW = "" + (char)100 + (char)100 + (char)100 + (char)100 + (char)100 + (char)0 + (char)20;
    static final String SW = "" + (char)100 + (char)100 + (char)100 + (char)0 + (char)0 + (char)0 + (char)1;

    static ArrayList<double[]> gunObss = new ArrayList<double[]>();
    static ArrayList<double[]> surfObss = new ArrayList<double[]>();
    static double[] scores = new double[FACTORS];
    static double dangerForward;
    static double dangerReverse;
    static W passingWave;

    double bulletVelocity;
    Point2D gunLocation;
    Point2D targetLocation;
    double startBearing;
    double bearingDirection;
    double distanceFromGun;
    boolean surfable;
    double[] obs;

    public boolean test() {
        advance(1);
        Pugilist r = Pugilist.robot;
        if (surfable) {
            if (passed(-20)) {
                passingWave = this;
            } else {
                r.updateDirectionStats(this);
            }
            if (passed(25)) {
                r.removeCustomEvent(this);
            }
        } else if (passed(-18)) {
            if (r.getOthers() > 0) {
                record(gunObss);
            }
            r.removeCustomEvent(this);
        }
        return false;
    }

    void record(ArrayList<double[]> obss) {
        obs[DIM_GF] = visitingIndex(targetLocation);
        obss.add(obs);
    }

    void query(ArrayList<double[]> obss) {
        dcFill(obss, obs, surfable ? SW : GW);
    }

    static void dcFill(ArrayList<double[]> obss, double[] q, String w) {
        scores = new double[FACTORS];
        try { for (int i = 0; ; i++) {
            double[] o = obss.get(i);
            double d = 0.01;
            for (int j = DIM_DIST; j < NUM_DIMS; j++)
                d += Math.abs(o[j] - q[j]) * w.charAt(j - 1);
            int gf = (int) o[DIM_GF];
            double score = (w.charAt(NUM_DIMS - 1) + i) / (d * d);
            for (int b = 0; b < FACTORS; b++)
                scores[b] += score / (Math.abs(gf - b) + 1);
        } } catch (Exception e) {}
    }

    static int bestGF() {
        int best = MIDDLE_FACTOR;
        try { for (int i = 0; ; i++) {
            if (scores[i] > scores[best])
                best = i;
        } } catch (Exception e) {}
        return best;
    }

    public boolean passed(double distanceOffset) {
        return distanceFromGun > gunLocation.distance(targetLocation) + distanceOffset;
    }

    void advance(int ticks) {
        distanceFromGun += ticks * bulletVelocity;
    }

    void initObs(double power, double direction) {
        bulletVelocity = 20 - 3 * power;
        bearingDirection = Math.asin(8 / bulletVelocity) * direction / MIDDLE_FACTOR;
    }

    int visitingIndex(Point2D target) {
        return (int)Math.clamp(Math.round(((Utils.normalRelativeAngle(gunBearing(target) - startBearing))
                        / bearingDirection) + (FACTORS - 1) / 2), 0, FACTORS - 1);
    }

    double gunBearing(Point2D target) {
        return Pugilist.absoluteBearing(gunLocation, target);
    }

    double distanceFromTarget(Point2D location, int timeOffset) {
        return gunLocation.distance(location) - distanceFromGun - timeOffset * bulletVelocity;
    }
}
