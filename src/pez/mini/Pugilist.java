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
        EW.passingWave = null;
        turnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        W wave = new W();
        EW ew = new EW();
        ew.gunLocation = project(enemyLocation, 0, 0);
        ew.startBearing = ew.gunBearing(robotLocation);

        double enemyDeltaEnergy = enemyEnergy - (enemyEnergy = e.getEnergy());
        if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= 3.0) {
            enemyFirePower = enemyDeltaEnergy;
            ew.surfable = true;
        }
        ew.bulletVelocity = 20 - 3 * enemyFirePower;

        double direction = robotBearingDirection(ew.startBearing);
        ew.calcBearingDirection(direction);

        int accelIndex = 1;
        if (robotVelocity != getVelocity()) {
            accelIndex = sign(robotVelocity - getVelocity()) + 1;
        }
        ew.visits = EW.factors
            [distanceIndex = (int)Math.min(W.DISTANCE_INDEXES - 1, enemyDistance / 180)]
            [(int)Math.abs(robotVelocity / 2)]
            [accelIndex];
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

        double bulletPower;
        wave.bulletVelocity = 20 - 3 * (bulletPower = Math.min(enemyEnergy / 4,
                    distanceIndex > 0 ? BULLET_POWER : MAX_BULLET_POWER));

        if (enemyVelocity != 0) {
            enemyBearingDirection = sign(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        wave.calcBearingDirection(enemyBearingDirection);

        wave.visits = W.factors[distanceIndex]
            [velocityIndex]
            [velocityIndex = (int)Math.abs(enemyVelocity / 2)]
            [(int)Math.clamp((long)(Math.pow(enemyTimeSinceVChange++, 0.45) - 1), 0, W.VCHANGE_TIME_INDEXES - 1)]
            [wallIndex(wave)];

        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
                    wave.bearingDirection * (wave.mostVisited() - W.MIDDLE_FACTOR)));

        addCustomEvent(wave);
        if (getEnergy() >= BULLET_POWER && Math.abs(getGunTurnRemainingRadians()) < Math.atan2(18, enemyDistance)) {
            setFire(bulletPower);
        }
        // </gun>

        if (EW.dangerReverse < EW.dangerForward) {
            direction = -direction;
        }
        double angle;
        setAhead(Math.cos(angle = wave.gunBearing(wallSmoothedDestination(robotLocation, direction)) - getHeadingRadians()) * 100);
        setTurnRightRadians(Math.tan(angle));

        setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
        EW.dangerForward = EW.dangerReverse = 0;
    }

    public void onHitByBullet(HitByBulletEvent e) {
        EW.passingWave.registerVisits();
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

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        Point2D destination = new Point2D.Double();
        for (;;) {
            double currentSmoothing = 0;
            while (currentSmoothing < 100 && !fieldRectangle.contains(destination = project(location,
                            absoluteBearing(location, enemyLocation) - direction * (Math.PI / 2 + 0.2 - (currentSmoothing++ / 100.0)),
                            enemyDistance / 5.0)))
                ;
            if (currentSmoothing < 45 || direction == 0) {
                break;
            }
            direction = 0;
        }
        return destination;
    }

    void updateDirectionStats(Condition condition) {
        EW wave = (EW) condition;
        EW.dangerForward += wave.danger(waveImpactLocation(wave, 1.0, 0));
        EW.dangerReverse += wave.danger(waveImpactLocation(wave, -1.0, 5));
    }

    Point2D waveImpactLocation(EW wave, double direction, int timeOffset) {
        Point2D impactLocation = project(robotLocation, 0, 0);
        do {
            impactLocation = project(impactLocation, absoluteBearing(impactLocation,
                        wallSmoothedDestination(impactLocation, direction * robotBearingDirection(wave.gunBearing(robotLocation)))), MAX_VELOCITY);
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
    static final int VELOCITY_INDEXES = 5;
    static final int WALL_INDEXES = 4;
    static final int VCHANGE_TIME_INDEXES = 6;
    static final int FACTORS = 31;
    static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

    static double[][][][][][] factors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][VELOCITY_INDEXES][VCHANGE_TIME_INDEXES][WALL_INDEXES][FACTORS];

    double bulletVelocity;
    Point2D gunLocation;
    Point2D targetLocation;
    double startBearing;
    double bearingDirection;
    double[] visits;
    double distanceFromGun;

    public boolean test() {
        advance(1);
        if (passed(-18)) {
            if (Pugilist.robot.getOthers() > 0) {
                registerVisits(visits, 1000);
            }
            Pugilist.robot.removeCustomEvent(this);
        }
        return false;
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
        return (int)Math.clamp(Math.round(((Utils.normalRelativeAngle(gunBearing(target) - startBearing)) /
                    bearingDirection) + (FACTORS - 1) / 2), 0, FACTORS - 1);
    }

    void registerVisits(double[] buffer, double depth) {
        for (int i = 1; i < FACTORS; i++) {
            buffer[i] = rollingAvg(buffer[i], i == visitingIndex(targetLocation) ? 100 : 0, depth);
        }
    }

    double gunBearing(Point2D target) {
        return Pugilist.absoluteBearing(gunLocation, target);
    }

    double distanceFromTarget(Point2D location, int timeOffset) {
        return gunLocation.distance(location) - distanceFromGun - timeOffset * bulletVelocity;
    }

    int mostVisited() {
        int mostVisited = MIDDLE_FACTOR, i = FACTORS - 1;
        do  {
            if (visits[--i] > visits[mostVisited]) {
                mostVisited = i;
            }
        } while (i > 0);
        return mostVisited;
    }

    static double rollingAvg(double value, double newEntry, double depth) {
        return (value * depth + newEntry) / (depth + 1.0);
    }
}

class EW extends W {
    static final int ACCEL_INDEXES = 3;
    static double[][][][] factors = new double[DISTANCE_INDEXES][VELOCITY_INDEXES][ACCEL_INDEXES][FACTORS];
    static double[] fastFactors = new double[FACTORS];
    static double dangerForward;
    static double dangerReverse;
    static EW passingWave;
    boolean surfable;

    void registerVisits() {
        registerVisits(visits, 1);
        registerVisits(fastFactors, 1);
    }

    public boolean test() {
        advance(1);
        if (passed(-20)) {
            surfable = false;
            passingWave = this;
        }
        if (passed(25)) {
            Pugilist.robot.removeCustomEvent(this);
        }
        if (surfable) {
            Pugilist.robot.updateDirectionStats(this);
        }
        return false;
    }

    double danger(Point2D destination) {
        double smoothed = 0;
        int i = 0;
        do {
            smoothed += (fastFactors[i] + visits[i] * 2) / Math.sqrt(Math.abs(visitingIndex(destination) - i) + 1.0);
            i++;
        } while (i < FACTORS);
        return smoothed / Math.abs(distanceFromTarget(targetLocation, 0)) / bulletVelocity;
    }
}
