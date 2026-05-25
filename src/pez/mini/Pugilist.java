package pez.mini;

import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;

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

    static Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
            BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
    static Point2D robotLocation = new Point2D.Double();
    static Point2D enemyLocation = new Point2D.Double();
    static double enemyDistance;
    static double enemyEnergy;
    static double enemyBearingDirection;
    static double prevEnemyVelocity;
    static double prevRobotVelocity;
    static int enemyTSVC, robotTSVC;

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
        if (prevRobotVelocity != robotVelocity) robotTSVC = 0; else robotTSVC++;
        ew.initObs(enemyFirePower, robotVelocity, prevRobotVelocity, robotLocation, direction, enemyLocation, robotTSVC);
        prevRobotVelocity = robotVelocity;
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
        double enemyVelocity = e.getVelocity();

        double bulletPower = enemyDistance < 175 ? MAX_BULLET_POWER : Math.clamp(enemyFirePower - 0.175, 0.1, 1.9);

        if (enemyVelocity != 0) {
            enemyBearingDirection = sign(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        if (prevEnemyVelocity != enemyVelocity) enemyTSVC = 0; else enemyTSVC++;
        wave.initObs(bulletPower, enemyVelocity, prevEnemyVelocity, enemyLocation, enemyBearingDirection,
                robotLocation, enemyTSVC);
        prevEnemyVelocity = enemyVelocity;

        wave.query();
        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
            wave.bearingDirection * (W.bestGF() - W.MIDDLE_FACTOR)));

        addCustomEvent(wave);
        if (getEnergy() >= bulletPower && Math.abs(getGunTurnRemainingRadians()) < 18.0 / enemyDistance) {
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
        W.passingWave.record();
    }

    // Surf: compute danger for forward/reverse using pre-smoothed scores
    void updateDirectionStats(W wave) {
        wave.query();
        double d = Math.abs(wave.distanceFromTarget(wave.targetLocation, 0)) * wave.bulletVelocity;
        W.dangerForward += W.scores[wave.visitingIndex(waveImpactLocation(wave, 1.0, 0))] / d;
        W.dangerReverse += W.scores[wave.visitingIndex(waveImpactLocation(wave, -1.0, 5))] / d;
    }

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        double s;
        for (;;) {
            s = wallSmooth(location, enemyLocation, direction);
            if (s < 45 || direction == 0)
                break;
            direction = 0;
        }
        return orbitProject(location, enemyLocation, direction, s - 1);
    }

    static Point2D orbitProject(Point2D from, Point2D toward, double direction, double w) {
        return project(from, absoluteBearing(from, toward)
                - direction * (Math.PI / 2 + 0.25 - (w / 100.0)), Math.clamp(enemyDistance / 1.7, 40.0, 150.0));
    }

    static double wallSmooth(Point2D from, Point2D toward, double direction) {
        double w = 0;
        while (w < 100 && !fieldRectangle.contains(orbitProject(from, toward, direction, w++)))
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
    static final int FACTORS = 29;
    static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
    static final int DISTANCE_INDEXES = 5, ACCEL_INDEXES = 5, VELOCITY_INDEXES = 9,
        WALL_INDEXES = 4, TSVC_INDEXES = 8;
    static final double GUN_DEPTH = 20, SURF_DEPTH = 1;

    static double[][][][][][][] gunFactors = new double[DISTANCE_INDEXES][ACCEL_INDEXES][VELOCITY_INDEXES][WALL_INDEXES][WALL_INDEXES][TSVC_INDEXES][FACTORS];
    static double[][][][][][][] surfFactors = new double[DISTANCE_INDEXES][ACCEL_INDEXES][VELOCITY_INDEXES][WALL_INDEXES][WALL_INDEXES][TSVC_INDEXES][FACTORS];
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
    double[] visits;

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
                record();
            }
            r.removeCustomEvent(this);
        }
        return false;
    }

    void record() {
        int gf = visitingIndex(targetLocation);
        for (int i = 0; i < FACTORS; i++)
            visits[i] = rollingAvg(visits[i], i == gf ? 100 : 0, surfable ? SURF_DEPTH : GUN_DEPTH);
    }

    void query() {
        scores = new double[FACTORS];
        for (int gf = 0; gf < FACTORS; gf++)
            for (int b = 0; b < FACTORS; b++)
                scores[b] += visits[gf] / (Math.abs(gf - b) + 1);
    }

    static double rollingAvg(double value, double newEntry, double depth) {
        return (value * depth + newEntry) / (depth + 1);
    }

    static int bestGF() {
        int best = MIDDLE_FACTOR;
        for (int i = 0; i < FACTORS; i++) {
            if (scores[i] > scores[best])
                best = i;
        }
        return best;
    }

    public boolean passed(double distanceOffset) {
        return distanceFromGun > gunLocation.distance(targetLocation) + distanceOffset;
    }

    void advance(int ticks) {
        distanceFromGun += ticks * bulletVelocity;
    }

    void initObs(double power, double vel, double prevVel, Point2D loc, double direction, Point2D orbitCenter, int tSVC) {
        bulletVelocity = 20 - 3 * power;
        bearingDirection = Math.asin(8 / bulletVelocity) * direction / MIDDLE_FACTOR;
        visits = buffer(surfable ? surfFactors : gunFactors, vel, prevVel, loc, direction, orbitCenter, tSVC);
    }

    static double[] buffer(double[][][][][][][] factors, double vel, double prevVel, Point2D loc,
            double direction, Point2D orbitCenter, int tSVC) {
        return factors
            [bin(Pugilist.enemyDistance, 160, DISTANCE_INDEXES)]
            [bin(prevVel - vel + 2, 1, ACCEL_INDEXES)]
            [bin(vel + 8, 2, VELOCITY_INDEXES)]
            [bin(Pugilist.wallSmooth(loc, orbitCenter, direction), 25, WALL_INDEXES)]
            [bin(Pugilist.wallSmooth(orbitCenter, loc, direction), 25, WALL_INDEXES)]
            [bin(tSVC, 8, TSVC_INDEXES)];
    }

    static int bin(double value, double scale, int indexes) {
        return (int) Math.clamp((long) (value / scale), 0, indexes - 1);
    }

    int visitingIndex(Point2D target) {
        return (int) Math.clamp((long)
                        (((Utils.normalRelativeAngle(gunBearing(target) - startBearing)) / bearingDirection)
                                + (FACTORS - 1) / 2 + 0.5), 0, FACTORS - 1);
    }

    double gunBearing(Point2D target) {
        return Pugilist.absoluteBearing(gunLocation, target);
    }

    double distanceFromTarget(Point2D location, int timeOffset) {
        return gunLocation.distance(location) - distanceFromGun - timeOffset * bulletVelocity;
    }
}
