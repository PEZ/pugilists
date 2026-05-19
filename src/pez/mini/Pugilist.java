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
        Wave.passingWave = null;
        turnRadarRightRadians(Double.POSITIVE_INFINITY);
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        Wave wave = new Wave();
        Wave ew = new Wave();
        ew.gunLocation = (Point2D) enemyLocation.clone();
        ew.startBearing = ew.gunBearing(robotLocation);

        double enemyDeltaEnergy = enemyEnergy - e.getEnergy();
        if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= 3.0) {
            enemyFirePower = enemyDeltaEnergy;
            ew.surfable = true;
        }
        enemyEnergy = e.getEnergy();

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
                project(wave.gunLocation = (Point2D) robotLocation.clone(), enemyAbsoluteBearing, enemyDistance));
        wave.targetLocation = enemyLocation;
        enemyDistance = e.getDistance();

        ew.advance(2);
        addCustomEvent(ew);

        // <gun>
        double enemyVelocity = e.getVelocity();

        double bulletPower = enemyDistance < 175 ? MAX_BULLET_POWER : Math.max(enemyFirePower - 0.175, 0.1);

        if (enemyVelocity != 0) {
            enemyBearingDirection = sign(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        if (prevEnemyVelocity != enemyVelocity) enemyTSVC = 0; else enemyTSVC++;
        wave.initObs(bulletPower, enemyVelocity, prevEnemyVelocity, enemyLocation, enemyBearingDirection,
                robotLocation, enemyTSVC);
        prevEnemyVelocity = enemyVelocity;

        wave.query(Wave.gunObss);
        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
                wave.bearingDirection * (Wave.bestGF() - Wave.MIDDLE_FACTOR)));

        addCustomEvent(wave);
        if (getEnergy() >= bulletPower && Math.abs(getGunTurnRemainingRadians()) < 18.0 / enemyDistance) {
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
        Wave.passingWave.record(Wave.surfObss);
    }

    // Surf: compute danger for forward/reverse using shared kernel
    void updateDirectionStats(Wave wave) {
        wave.query(Wave.surfObss);
        double d = Math.abs(wave.distanceFromTarget(wave.targetLocation, 0)) * wave.bulletVelocity;
        Wave.dangerForward += Wave.scores[wave.visitingIndex(waveImpactLocation(wave, 1.0, 0))] / d;
        Wave.dangerReverse += Wave.scores[wave.visitingIndex(waveImpactLocation(wave, -1.0, 5))] / d;
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

    Point2D waveImpactLocation(Wave wave, double direction, int timeOffset) {
        Point2D impactLocation = (Point2D) robotLocation.clone();
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

class Wave extends Condition {
    static final int FACTORS = 29;
    static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
    static final String GW = "" + (char)100 + (char)100 + (char)100 + (char)100 + (char)100 + (char)50 + (char)20;
    static final String SW = "" + (char)100 + (char)100 + (char)100 + (char)100 + (char)100 + (char)50 + (char)1;

    static ArrayList<double[]> gunObss = new ArrayList<double[]>();
    static ArrayList<double[]> surfObss = new ArrayList<double[]>();
    static double[] scores = new double[FACTORS];
    static double dangerForward;
    static double dangerReverse;
    static Wave passingWave;

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
        if (surfable) {
            if (passed(-20)) {
                passingWave = this;
            } else {
                Pugilist.robot.updateDirectionStats(this);
            }
            if (passed(25)) {
                Pugilist.robot.removeCustomEvent(this);
            }
        } else if (passed(-18)) {
            if (Pugilist.robot.getOthers() > 0) {
                record(gunObss);
            }
            Pugilist.robot.removeCustomEvent(this);
        }
        return false;
    }

    void record(ArrayList<double[]> obss) {
        obs[0] = visitingIndex(targetLocation);
        obss.add(obs);
    }

    void query(ArrayList<double[]> obss) {
        dcFill(obss, obs, surfable ? SW : GW);
    }

    static void dcFill(ArrayList<double[]> obss, double[] q, String w) {
        scores = new double[FACTORS];
        for (int i = 0; i < obss.size(); i++) {
            double[] o = obss.get(i);
            double d = 0.01;
            for (int j = 1; j < 7; j++)
                d += Math.abs(o[j] - q[j]) * w.charAt(j - 1);
            scores[(int) o[0]] += (w.charAt(6) + i) / (d * d);
        }
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
        obs = new double[] { 0, Pugilist.enemyDistance / 180.0,
            (prevVel - vel) * 1.5,
            vel / 2.0,
            Pugilist.wallSmooth(loc, orbitCenter, direction) / 25.0,
            Pugilist.wallSmooth(orbitCenter, loc, direction) / 25.0,
            Math.sqrt(tSVC) };
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
