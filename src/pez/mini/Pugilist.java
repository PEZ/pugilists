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
    static final double BULLET_POWER = 1.9;
    static final double MAX_BULLET_POWER = 3.0;

    static Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
            BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
    static Point2D robotLocation = new Point2D.Double();
    static Point2D enemyLocation = new Point2D.Double();
    static double enemyDistance;
    static int distanceIndex;
    static int velocityIndex;
    static double enemyVelocity;
    static double enemyEnergy;
    static double enemyBearingDirection;
    static int enemyTSVC;

    static double enemyFirePower = MAX_BULLET_POWER;
    static double robotVelocity;
    static int robotBD = 1;
    static Pugilist robot;

    public void run() {
        setAdjustRadarForGunTurn(true);
        setAdjustGunForRobotTurn(true);
        robot = this;
        Wave.passingWave = null;
        while (true) {
            turnRadarRightRadians(100);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        Wave wave = new Wave();
        Wave ew = new Wave();
        ew.gunLocation = project(enemyLocation, 0, 0);
        ew.startBearing = ew.gunBearing(robotLocation);

        double enemyDeltaEnergy = enemyEnergy - (enemyEnergy = e.getEnergy());
        if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= 3.0) {
            enemyFirePower = enemyDeltaEnergy;
            ew.surfable = true;
        }

        double direction = robotBearingDirection(ew.startBearing);
        ew.bulletVelocity = 20 - 3 * enemyFirePower;
        ew.calcBearingDirection(direction);
        int accelIndex = 1;
        if (robotVelocity != getVelocity()) {
            accelIndex = sign(robotVelocity - getVelocity()) + 1;
        }
        ew.enemyWave = true;
        ew.visits = Wave.surfFactors[distanceIndex = (int) Math.min(Wave.DISTANCE_INDEXES - 1, enemyDistance / 180)]
            [(int) Math.abs(robotVelocity)][accelIndex];
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
            enemyTSVC = 0;
        }

        double bulletPower = enemyDistance < 175 ? MAX_BULLET_POWER
                : Math.clamp(enemyFirePower - 0.175, 0.1, BULLET_POWER);

        if (enemyVelocity != 0) {
            enemyBearingDirection = sign(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        wave.bulletVelocity = 20 - 3 * bulletPower;
        wave.calcBearingDirection(enemyBearingDirection);
        wave.visits = Wave.gunFactors[distanceIndex]
                [velocityIndex]
                [velocityIndex = (int) Math.abs(enemyVelocity)]
                [(int) Math.clamp((long) (Math.pow(enemyTSVC++, 0.45) - 1), 0, Wave.VCHANGE_TIME_INDEXES - 1)]
                [wallIndex(wave)];

        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
                wave.bearingDirection * (wave.mostVisited() - Wave.MIDDLE_FACTOR)));

        addCustomEvent(wave);
        if (getEnergy() >= bulletPower) {
            setFire(bulletPower);
        }
        // </gun>

        if (Wave.dangerReverse < Wave.dangerForward) {
            direction = -direction;
        }
        double angle;
        setAhead(Math
                .cos(angle = wave.gunBearing(wallSmoothedDestination(robotLocation, direction)) - getHeadingRadians())
                * 100);
        setTurnRightRadians(Math.tan(angle));

        setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
        Wave.dangerForward = Wave.dangerReverse = 0;
    }

    public void onHitByBullet(HitByBulletEvent e) {
        Wave.passingWave.registerVisits();
    }

    static int wallIndex(Wave wave) {
        int wallIndex = 0;
        do {
            wallIndex++;
        } while (wallIndex < Wave.WALL_INDEXES && fieldRectangle.contains(project(wave.gunLocation,
                wave.startBearing + wave.bearingDirection * (wallIndex * 5.5), enemyDistance)));
        return wallIndex - 1;
    }

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        double s = wallSmooth(location, enemyLocation, direction);
        if (s >= 45) {
            double rs = wallSmooth(location, enemyLocation, -direction);
            if (rs < s) {
                direction = -direction;
                s = rs;
            }
        }
        return orbitProject(location, enemyLocation, direction, s - 1);
    }

    static Point2D orbitProject(Point2D from, Point2D toward, double direction, double w) {
        return project(from, absoluteBearing(from, toward)
                - direction * (Math.PI / 2 + 0.2 - (w / 100.0)), enemyDistance / 5);
    }

    static double wallSmooth(Point2D from, Point2D toward, double direction) {
        double w = 0;
        while (w < 100 && !fieldRectangle.contains(orbitProject(from, toward, direction, w++)))
            ;
        return w;
    }

    double robotBearingDirection(double enemyBearing) {
        double v = getVelocity() * Math.sin(getHeadingRadians() - enemyBearing);
        return v != 0 ? (robotBD = sign(v)) : robotBD;
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

    static class Wave extends Condition {
        static final int DISTANCE_INDEXES = 5;
        static final int VELOCITY_INDEXES = 9;
        static final int WALL_INDEXES = 4;
        static final int VCHANGE_TIME_INDEXES = 6;
        static final int ACCEL_INDEXES = 3;
        static final int FACTORS = 31;
        static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
        static double[][][][][][] gunFactors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][WALL_INDEXES][FACTORS];
        static double[][][][] surfFactors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][ACCEL_INDEXES][FACTORS];
        static double[] fastFactors = new double[FACTORS];
        static double dangerForward;
        static double dangerReverse;
        static Wave passingWave;

        double bulletVelocity;
        Point2D gunLocation;
        Point2D targetLocation;
        double startBearing;
        double bearingDirection;
        double distanceFromGun;
        boolean enemyWave;
        boolean surfable;
        double[] visits;

        public boolean test() {
            advance(1);
            Pugilist r = Pugilist.robot;
            if (enemyWave) {
                if (passed(-20)) {
                    surfable = false;
                    passingWave = this;
                }
                if (passed(25)) {
                    r.removeCustomEvent(this);
                }
                if (surfable) {
                    Wave.dangerForward += danger(impactLocation(1.0, 0));
                    Wave.dangerReverse += danger(impactLocation(-1.0, 5));
                }
            } else if (passed(-18)) {
                if (r.getOthers() > 0) {
                    registerVisits(visits, 1000);
                }
                r.removeCustomEvent(this);
            }
            return false;
        }

        void registerVisits() {
            registerVisits(visits, 1);
            registerVisits(fastFactors, 1);
        }

        public boolean passed(double distanceOffset) {
            return distanceFromGun > gunLocation.distance(targetLocation) + distanceOffset;
        }

        void advance(int ticks) {
            distanceFromGun += ticks * bulletVelocity;
        }

        void calcBearingDirection(double direction) {
            bearingDirection = Math.asin(8 / bulletVelocity) * direction / MIDDLE_FACTOR;
        }

        int visitingIndex(Point2D target) {
            return (int) Math
                    .clamp(Math.round(((Utils.normalRelativeAngle(gunBearing(target) - startBearing)) / bearingDirection)
                            + (FACTORS - 1) / 2), 0, FACTORS - 1);
        }

        void registerVisits(double[] buffer, double depth) {
            for (int i = 1; i < FACTORS; i++) {
                buffer[i] = rollingAvg(buffer[i], i == visitingIndex(targetLocation) ? 100 : 0, depth);
            }
        }

        int mostVisited() {
            int mostVisited = MIDDLE_FACTOR, i = FACTORS - 1;
            do {
                if (visits[--i] > visits[mostVisited]) {
                    mostVisited = i;
                }
            } while (i > 0);
            return mostVisited;
        }

        static double rollingAvg(double value, double newEntry, double n) {
            return (value * n + newEntry) / (n + 1.0);
        }

        double gunBearing(Point2D target) {
            return absoluteBearing(gunLocation, target);
        }

        double distanceFromTarget(Point2D location, int timeOffset) {
            return gunLocation.distance(location) - distanceFromGun - timeOffset * bulletVelocity;
        }

        Point2D impactLocation(double direction, int timeOffset) {
            Point2D loc = robotLocation;
            do {
                loc = project(loc, absoluteBearing(loc,
                        wallSmoothedDestination(loc,
                                direction * robot.robotBearingDirection(gunBearing(robotLocation)))),
                        MAX_VELOCITY);
                timeOffset++;
            } while (distanceFromTarget(loc, timeOffset) > -8);
            return loc;
        }

        double danger(Point2D destination) {
            double smoothed = 0;
            int i = 0;
            do {
                smoothed += (fastFactors[i] + visits[i] * 2) / Math.sqrt((Math.abs(visitingIndex(destination) - i) + 1.0));
                i++;
            } while (i < FACTORS);
            return smoothed / Math.abs(distanceFromTarget(targetLocation, 0)) / bulletVelocity;
        }
    }
}
