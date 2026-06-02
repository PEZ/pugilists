package pez.mini2;

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
    static final double BULLET_POWER = 1.9;
    static final double MAX_BULLET_POWER = 3.0;
    static final double BOT_WIDTH = 40;
    static final double WALL_MARGIN = 20;
    static final double WALL_MARGIN_RAMMER = 100;
    static final int MAX_WALL_SMOOTH = 97;
    static final int WALL_SMOOTH_DIVISOR = 48;
    static final int WALL_SMOOTH_LIMIT = 96;

    static Point2D robotLocation = new Point2D.Double();
    static Point2D enemyLocation = new Point2D.Double();
    static double enemyDistance;
    static int velocityIndex;
    static double enemyVelocity;
    static double enemyEnergy;
    static double enemyBearingDirection;
    static int enemyTSVC;
    static double approach, ramLean;

    static int wallSmoothSurf;
    static double enemyFirePower = MAX_BULLET_POWER;
    static double robotVelocity;
    static double robotBD = 1;

    public void run() {
        setAdjustRadarForGunTurn(true);
        setAdjustGunForRobotTurn(true);
        Wave.passingWave = null;
        while (true) { // Loop neeede if the radar "slips" off the enemy
            turnRadarRightRadians(100);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        Wave ew = new Wave();
        Wave wave = new Wave();
        ew.robot = wave.robot = this;

        // <movement>
        addCustomEvent(ew);
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
        ew.enemyWave = true;
        int distanceIndex = (int) Math.min(Wave.DISTANCE_INDEXES - 1, enemyDistance / 180);
        ew.targetLocation = robotLocation;

        robotLocation.setLocation(getX(), getY());

        double enemyAbsoluteBearing;

        if (enemyVelocity != (enemyVelocity = e.getVelocity())) {
            enemyTSVC = 0;
        }

        double angle = e.getHeadingRadians()
                - (wave.startBearing = enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians());
        ramLean = (approach = (approach * 4
                - enemyVelocity * Math.cos(angle)) / 5) > 4.5 ? 0.3 : 0;
        enemyLocation.setLocation(
                project(wave.gunLocation = project(robotLocation, 0, 0), enemyAbsoluteBearing, enemyDistance));
        wave.targetLocation = enemyLocation;
        enemyDistance = e.getDistance();

        ew.advance(2);

        if (Wave.dangerReverse < Wave.dangerForward) {
            direction = -direction;
        }
        double wallAngle;
        setAhead(Math
                .cos(wallAngle = wave.gunBearing(wallSmoothedDestination(robotLocation, direction))
                        - getHeadingRadians())
                * 100);
        setTurnRightRadians(Math.tan(wallAngle));
        setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);

        ew.visits = Wave.surfFactors[distanceIndex][(int) Math.abs(robotVelocity / 2)][(int) Math
                .abs(robotVelocity = getVelocity() / 2)][wallSmoothIndex(wallSmoothSurf)];

        Wave.dangerForward = Wave.dangerReverse = 0;
        // </movement>

        // <gun>
        addCustomEvent(wave);

        double bulletPower = Math.min(enemyEnergy / 4,
                ramLean > 0 || enemyDistance < 175 ? MAX_BULLET_POWER
                        : Math.clamp(Math.min(enemyFirePower - 0.175, 700 / enemyDistance), 0.1, BULLET_POWER));

        if (enemyVelocity != 0) {
            enemyBearingDirection = Math.signum(enemyVelocity * Math.sin(angle));
        }
        wave.bulletVelocity = 20 - 3 * bulletPower;
        wave.calcBearingDirection(enemyBearingDirection);
        wave.visits = Wave.gunFactors[distanceIndex][velocityIndex][velocityIndex = (int) Math
                .abs(enemyVelocity / 2)][(int) Math.min((int) Math.sqrt(enemyTSVC++),
                        Wave.VCHANGE_TIME_INDEXES - 1)][wallSmoothIndex(
                                wallSmooth(enemyLocation, robotLocation, enemyBearingDirection))][wallSmoothIndex(
                                        wallSmooth(enemyLocation, robotLocation, -enemyBearingDirection))];

        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
                wave.bearingDirection * (wave.mostVisited() - Wave.MIDDLE_FACTOR)
                + Math.random() * 0.007));

        setFireBullet(bulletPower);
        // </gun>
    }

    public void onHitByBullet(HitByBulletEvent e) {
        Wave pw = Wave.passingWave;
        pw.registerVisits(pw.visits, 5);
        pw.registerVisits(Wave.fastFactors, 1);
    }

    static int wallSmoothIndex(int smoothing) {
        return smoothing / WALL_SMOOTH_DIVISOR;
    }

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        int s = wallSmooth(location, enemyLocation, direction);
        if (s >= WALL_SMOOTH_LIMIT) {
            int rs = wallSmooth(location, enemyLocation, -direction);
            if (rs < s) {
                direction = -direction;
                s = rs;
            }
        }
        wallSmoothSurf = s;
        // System.out.println("wall smooth: " + s);

        return orbitProject(location, enemyLocation, direction, s - 1);
    }

    static Point2D orbitProject(Point2D from, Point2D toward, double direction, double w) {
        return project(from, absoluteBearing(from, toward)
                - direction * (1.7707963267948966 + ramLean + Math.min(0.4, 30 / enemyDistance) - (w * 0.01)),
                Math.max(30, enemyDistance / 5));
    }

    static int wallSmooth(Point2D from, Point2D toward, double direction) {
        int w = 0;
        double margin = ramLean > 0 ? WALL_MARGIN_RAMMER : WALL_MARGIN;
        Point2D p;
        while (w < MAX_WALL_SMOOTH && ((p = orbitProject(from, toward, direction, w++)).getX() < margin
                || p.getX() > BATTLE_FIELD_WIDTH - margin || p.getY() < margin || p.getY() > BATTLE_FIELD_HEIGHT - margin))
            ;
        return w;
    }

    double robotBearingDirection(double enemyBearing) {
        double v = getVelocity() * Math.sin(getHeadingRadians() - enemyBearing);
        return v != 0 ? (robotBD = Math.signum(v)) : robotBD;
    }

    static Point2D project(Point2D sourceLocation, double angle, double length) {
        return new Point2D.Double(sourceLocation.getX() + Math.sin(angle) * length,
                sourceLocation.getY() + Math.cos(angle) * length);
    }

    static double absoluteBearing(Point2D source, Point2D target) {
        return Math.atan2(target.getX() - source.getX(), target.getY() - source.getY());
    }

    static class Wave extends Condition {
        static final int DISTANCE_INDEXES = 5;
        static final int VELOCITY_INDEXES = 5;
        static final int WALL_INDEXES = 3;
        static final int VCHANGE_TIME_INDEXES = 5;
        static final int FACTORS = 45;
        static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
        static double[][][][][][][] gunFactors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][WALL_INDEXES][WALL_INDEXES][FACTORS];
        static double[][][][][] surfFactors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][WALL_INDEXES][FACTORS];
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
        Pugilist robot;

        public boolean test() {
            advance(1);
            double td = gunLocation.distance(targetLocation);
            if (enemyWave) {
                if (distanceFromGun > td - 25) {
                    surfable = false;
                    passingWave = this;
                }
                if (distanceFromGun > td + 25) {
                    return true;
                }
                if (surfable) {
                    Wave.dangerForward += danger(impactLocation(1, 0));
                    Wave.dangerReverse += danger(impactLocation(-1, 5));
                }
            } else if (distanceFromGun > td) {
                registerVisits(visits, 100);
                return true;
            }
            return false;
        }

        void advance(int ticks) {
            distanceFromGun += ticks * bulletVelocity;
        }

        void calcBearingDirection(double direction) {
            bearingDirection = Math.asin(8 / bulletVelocity) * direction / MIDDLE_FACTOR;
        }

        int visitingIndex(Point2D target) {
            return (int) Math.clamp(
                    Utils.normalRelativeAngle(gunBearing(target) - startBearing) / bearingDirection + 22.5, 0.0, 44.0);
        }

        void registerVisits(double[] buffer, double depth) {
            int vi = visitingIndex(targetLocation);
            try {
                for (int i = 1;; i++) {
                    buffer[i] = (buffer[i] * depth + 100.0 / (Math.abs(vi - i) + 1.0)) / (depth + 1.0);
                }
            } catch (Exception e) {
            }
        }

        int mostVisited() {
            int mostVisited = MIDDLE_FACTOR, i = FACTORS - 1;
            try {
                for (;;) {
                    if (visits[--i] > visits[mostVisited]) {
                        mostVisited = i;
                    }
                }
            } catch (Exception e) {
            }
            return mostVisited;
        }

        double gunBearing(Point2D target) {
            return absoluteBearing(gunLocation, target);
        }

        double distanceFromTarget(Point2D location, int timeOffset) {
            return gunLocation.distance(location) - distanceFromGun - timeOffset * bulletVelocity;
        }

        Point2D impactLocation(int direction, int timeOffset) {
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
            int vi = visitingIndex(destination);
            return (fastFactors[vi] + visits[vi] * 2)
                    / distanceFromTarget(targetLocation, 0) / bulletVelocity;
        }
    }
}
