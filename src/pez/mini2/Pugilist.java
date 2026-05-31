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
    static final double WALL_MARGIN = 20;
    static final double BULLET_POWER = 1.9;
    static final double MAX_BULLET_POWER = 3.0;
    static final int MAX_WALL_SMOOTH = 97;
    static final double BOT_WIDTH = 40;

    static Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
            BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
    static Point2D robotLocation = new Point2D.Double();
    static Point2D enemyLocation = new Point2D.Double();
    static double enemyDistance;
    static int velocityIndex;
    static double enemyVelocity;
    static double enemyEnergy;
    static double enemyBearingDirection;
    static int enemyTSVC;

    static int wallSmoothSurf;
    static double enemyFirePower = MAX_BULLET_POWER;
    static double robotVelocity;
    static double robotBD = 1;
    static Pugilist robot;

    public void run() {
        setAdjustRadarForGunTurn(true);
        setAdjustGunForRobotTurn(true);
        robot = this;
        Wave.passingWave = null;
        while (true) { // Loop neeede if the radar "slips" off the enemy
            turnRadarRightRadians(100);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        Wave ew = new Wave();
        Wave wave = new Wave();

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
        wave.startBearing = enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
        enemyLocation.setLocation(
                project(wave.gunLocation = project(robotLocation, 0, 0), enemyAbsoluteBearing, enemyDistance));
        wave.targetLocation = enemyLocation;
        enemyDistance = e.getDistance();

        ew.advance(2);

        if (Wave.dangerReverse < Wave.dangerForward) {
            direction = -direction;
        }
        double angle;
        setAhead(Math
                .cos(angle = wave.gunBearing(wallSmoothedDestination(robotLocation, direction)) - getHeadingRadians())
                * 100);
        setTurnRightRadians(Math.tan(angle));
        setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);

        ew.visits = Wave.surfFactors[(int) Math.min(3, enemyDistance / 200)][(int) Math
                .abs(robotVelocity = getVelocity() / 2)];

        Wave.dangerForward = Wave.dangerReverse = 0;
        // </movement>

        // <gun>
        addCustomEvent(wave);

        if (enemyVelocity != (enemyVelocity = e.getVelocity())) {
            enemyTSVC = 0;
        }

        double bulletPower = Math.min(enemyEnergy / 4,
                enemyDistance < 175 ? MAX_BULLET_POWER
                        : Math.clamp(enemyFirePower - 0.175, 0.1, BULLET_POWER));

        if (enemyVelocity != 0) {
            enemyBearingDirection = Math.signum(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        wave.bulletVelocity = 20 - 3 * bulletPower;
        wave.calcBearingDirection(enemyBearingDirection);
        wave.visits = Wave.gunFactors[distanceIndex][velocityIndex][velocityIndex = (int) Math
                .abs(enemyVelocity / 2)][(int) Math.min((int) Math.pow(enemyTSVC++, 0.45),
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
        Wave.passingWave.registerVisits(Wave.passingWave.visits, 5);
        Wave.passingWave.registerVisits(Wave.fastFactors, 1);
    }

    static int wallSmoothIndex(int smoothing) {
        return smoothing / (MAX_WALL_SMOOTH / (Wave.WALL_INDEXES - 1));
    }

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        int s = wallSmooth(location, enemyLocation, direction);
        if (s >= MAX_WALL_SMOOTH - 1) {
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
                - direction * (Math.PI / 2 + 0.2 + Math.min(0.4, 30 / enemyDistance) - (w / 100.0)),
                Math.max(30, enemyDistance / 5));
    }

    static int wallSmooth(Point2D from, Point2D toward, double direction) {
        int w = 0;
        while (w < MAX_WALL_SMOOTH && !fieldRectangle.contains(orbitProject(from, toward, direction, w++)))
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
        static final int FACTORS = 31;
        static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
        static double[][][][][][][] gunFactors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][WALL_INDEXES][WALL_INDEXES][FACTORS];
        static double[][][] surfFactors = new double[4][VELOCITY_INDEXES][FACTORS];
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
                if (passed(-25)) {
                    surfable = false;
                    passingWave = this;
                }
                if (passed(25)) {
                    r.removeCustomEvent(this);
                }
                if (surfable) {
                    Wave.dangerForward += danger(impactLocation(1, 0));
                    Wave.dangerReverse += danger(impactLocation(-1, 5));
                }
            } else if (passed(0)) {
                if (r.getOthers() > 0) {
                    registerVisits(visits, 500);
                }
                r.removeCustomEvent(this);
            }
            return false;
        }

        public boolean passed(int distanceOffset) {
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
                    .clamp(Math
                            .round(((Utils.normalRelativeAngle(gunBearing(target) - startBearing)) / bearingDirection)
                                    + (FACTORS - 1) / 2),
                            0, FACTORS - 1);
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
                    / Math.abs(distanceFromTarget(targetLocation, 0)) / bulletVelocity;
        }
    }
}
