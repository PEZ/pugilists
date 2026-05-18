package pez.mini;
import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;
import java.util.ArrayList;

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
    static double enemyEnergy;
    static double enemyBearingDirection;
    static double prevEnemyVelocity;
    static double prevRobotVelocity;

    static double enemyFirePower = BULLET_POWER;
    static double robotVelocity;
    static Pugilist robot;

    public void run() {
        setAdjustRadarForGunTurn(true);
        setAdjustGunForRobotTurn(true);
        robot = this;
        EnemyWave.passingWave = null;
        while (true) {
            turnRadarRightRadians(Double.POSITIVE_INFINITY);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        Wave wave = new Wave();
        EnemyWave ew = new EnemyWave();
        ew.gunLocation = (Point2D)enemyLocation.clone();
        ew.startBearing = ew.gunBearing(robotLocation);

        double enemyDeltaEnergy = enemyEnergy - e.getEnergy();
        if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= 3.0) {
            enemyFirePower = enemyDeltaEnergy;
            ew.surfable = true;
        }
        enemyEnergy = e.getEnergy();

        double direction = robotBearingDirection(ew.startBearing);
        ew.initObs(enemyFirePower, robotVelocity, prevRobotVelocity, robotLocation, direction);
        prevRobotVelocity = robotVelocity;
        robotVelocity = getVelocity();
        ew.targetLocation = robotLocation;

        robotLocation.setLocation(getX(), getY());

        double enemyAbsoluteBearing;
        wave.startBearing = enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
        enemyLocation.setLocation(project(wave.gunLocation = (Point2D)robotLocation.clone(), enemyAbsoluteBearing, enemyDistance));
        wave.targetLocation = enemyLocation;
        enemyDistance = e.getDistance();

        ew.advance(2);
        addCustomEvent(ew);

        // <gun>
        double enemyVelocity = e.getVelocity();

        double bulletPower = enemyFirePower > 0.3 ? Math.min(enemyEnergy / 4, enemyDistance > 180 ? BULLET_POWER : MAX_BULLET_POWER) : 0.1;

        if (enemyVelocity != 0) {
            enemyBearingDirection = sign(enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
        }
        wave.initObs(bulletPower, enemyVelocity, prevEnemyVelocity, enemyLocation, enemyBearingDirection);
        prevEnemyVelocity = enemyVelocity;

        wave.query(Wave.gunObs);
        setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
                    wave.bearingDirection * (Wave.bestGF() - Wave.MIDDLE_FACTOR)));

        addCustomEvent(wave);
        if (getEnergy() >= bulletPower && Math.abs(getGunTurnRemainingRadians()) < 18.0 / enemyDistance) {
            setFire(bulletPower);
        }
        // </gun>

        if (EnemyWave.dangerReverse < EnemyWave.dangerForward) {
            direction = -direction;
        }
        double angle;
        setAhead(Math.cos(angle = wave.gunBearing(wallSmoothedDestination(robotLocation, direction)) - getHeadingRadians()) * 100);
        setTurnRightRadians(Math.tan(angle));

        setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
        EnemyWave.dangerForward = EnemyWave.dangerReverse = 0;
    }

    public void onHitByBullet(HitByBulletEvent e) {
        EnemyWave.passingWave.record(EnemyWave.surfObs);
    }

    // Surf: compute danger for forward/reverse using shared kernel
    void updateDirectionStats(EnemyWave wave) {
        wave.query(EnemyWave.surfObs);
        double d = Math.abs(wave.distanceFromTarget(wave.targetLocation, 0)) * wave.bulletVelocity;
        EnemyWave.dangerForward += Wave.scores[wave.visitingIndex(waveImpactLocation(wave, 1.0, 0))] / d;
        EnemyWave.dangerReverse += Wave.scores[wave.visitingIndex(waveImpactLocation(wave, -1.0, 5))] / d;
    }

    static Point2D wallSmoothedDestination(Point2D location, double direction) {
        Point2D destination = new Point2D.Double();
        for (;;) {
            double currentSmoothing = 0;
            while (currentSmoothing < 100 && !fieldRectangle.contains(destination = project(location, absoluteBearing(location, enemyLocation) -
                            direction*(Math.PI / 2 + 0.2 - (currentSmoothing++ / 100.0)), enemyDistance / 5.0)));
            if (currentSmoothing < 45 || direction == 0) {
                break;
            }
            direction = 0;
        }
        return destination;
    }

    Point2D waveImpactLocation(EnemyWave wave, double direction, int timeOffset) {
        Point2D impactLocation = (Point2D)robotLocation.clone();
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

class Wave extends Condition {
    static final int FACTORS = 31;
    static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

    // Shared observation lists (each entry: double[]{gf, dist, vel, third})
    static ArrayList<double[]> gunObs = new ArrayList<double[]>();
    static double[] scores = new double[FACTORS];

    double bulletVelocity;
    Point2D gunLocation;
    Point2D targetLocation;
    double startBearing;
    double bearingDirection;
    double distanceFromGun;

    // Per-wave observation attributes (pre-normalized)
    double obsDist;
    double obsPrevVel;
    double obsVel;
    double obsWall;

    public boolean test() {
        advance(1);
        if (passed(-18)) {
            if (Pugilist.robot.getOthers() > 0) {
                record(gunObs);
            }
            Pugilist.robot.removeCustomEvent(this);
        }
        return false;
    }

    void record(ArrayList<double[]> obs) {
        obs.add(new double[]{visitingIndex(targetLocation), obsDist, obsPrevVel, obsVel, obsWall});
    }

    void query(ArrayList<double[]> obs) {
        dcFill(obs, obsDist, obsPrevVel, obsVel, obsWall);
    }

    static void dcFill(ArrayList<double[]> obs, double dist, double prevVel, double vel, double wall) {
        scores = new double[FACTORS];
        for (int i = 0; i < obs.size(); i++) {
            double[] o = obs.get(i);
            double d = Math.abs(o[1] - dist) + Math.abs(o[2] - prevVel) + Math.abs(o[3] - vel) + Math.abs(o[4] - wall) + 0.01;
            scores[(int)o[0]] += (1.0 + i) / (d * d);
        }
    }

    static int bestGF() {
        int best = MIDDLE_FACTOR;
        for (int i = 0; i < FACTORS; i++) {
            if (scores[i] > scores[best]) best = i;
        }
        return best;
    }

    public boolean passed(double distanceOffset) {
        return distanceFromGun > gunLocation.distance(targetLocation) + distanceOffset;
    }

    void advance(int ticks) {
        distanceFromGun += ticks * bulletVelocity;
    }

    void initObs(double power, double vel, double prevVel, Point2D loc, double direction) {
        bulletVelocity = 20 - 3 * power;
        bearingDirection = Math.asin(8 / bulletVelocity) * direction / MIDDLE_FACTOR;
        obsDist = Pugilist.enemyDistance / 200.0;
        obsPrevVel = (vel - prevVel) / 4.0;
        obsVel = vel / 4.0;
        obsWall = Math.min(Math.min(loc.getX(), loc.getY()), Math.min(800 - loc.getX(), 600 - loc.getY())) / 200.0;
    }

    int visitingIndex(Point2D target) {
        return Math.max(0, Math.min(FACTORS - 1, (int)(((Utils.normalRelativeAngle(gunBearing(target) - startBearing)) / bearingDirection) + (FACTORS - 1) / 2 + 0.5)));
    }

    double gunBearing(Point2D target) {
        return Pugilist.absoluteBearing(gunLocation, target);
    }

    double distanceFromTarget(Point2D location, int timeOffset) {
        return gunLocation.distance(location) - distanceFromGun - timeOffset * bulletVelocity;
    }
}

class EnemyWave extends Wave {
    static ArrayList<double[]> surfObs = new ArrayList<double[]>();

    static double dangerForward;
    static double dangerReverse;
    static EnemyWave passingWave;
    boolean surfable;

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
}
