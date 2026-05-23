package pez.micro;

import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;
import java.util.ArrayList;

// This code is released under the RoboWiki Public Code Licence (RWPCL), datailed on:
// http://robowiki.net/?RWPCL
//
// Aristocles, by PEZ. What you see is always an imperfect copy of the form. 
// $Id: Aristocles.java,v 1.11 2004/02/22 20:10:06 peter Exp $

public class Jackson extends AdvancedRobot {
	static final double BATTLE_FIELD_WIDTH = 800;
	static final double BATTLE_FIELD_HEIGHT = 600;

	static final double MAX_DISTANCE = 900;
	static final double MAX_BULLET_POWER = 3.0;
	static final double BULLET_POWER = 1.9;
	static final double WALL_MARGIN = 18;

	static final int DISTANCE_INDEXES = 10;
	static final int VELOCITY_INDEXES = 10;
	static final int FACTORS = 37;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;
	static final String GW = "" + (char) 1 + (char) 200 + (char) 50 + (char) 18 + (char) 18 + (char) 16 + (char) 20;
	static final String SW = "" + (char) 1 + (char) 200 + (char) 50 + (char) 18 + (char) 18 + (char) 16 + (char) 1;

	static Point2D currentEnemyLocation;
	static double enemyBearingDirection;
	static double myX, myY;
	static double[] scores = new double[FACTORS];
	static ArrayList<double[]> aimFactors = new ArrayList<double[]>();
	static ArrayList<double[]> realMovementFactors = new ArrayList<double[]>();
	static double direction = 1;
	static double enemyEnergy;
	static double lastVelocity;
	static double prevEnemyVelocity, prevRobotVelocity, enemyDistance;
	static int enemyTSVC, robotTSVC;
	static Wave enemyWave;

	public void run() {
		setAdjustRadarForGunTurn(true);
		setAdjustGunForRobotTurn(true);
		enemyEnergy = 102;
		turnRadarRightRadians(Double.POSITIVE_INFINITY);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		myX = getX();
		myY = getY();
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		currentEnemyLocation = project(enemyAbsoluteBearing, enemyDistance = e.getDistance());
		Point2D myLocation = new Point2D.Double(myX, myY);
		double movementStartBearing = absoluteBearing(currentEnemyLocation, myLocation);
		double movementBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
				lastVelocity * Math.sin(getHeadingRadians() - movementStartBearing));
		if (prevRobotVelocity != lastVelocity) robotTSVC = 0; else robotTSVC++;
		double robotBearingDirection = Math.signum(movementBearingDirection);
		lastVelocity = getVelocity();
		double enemyDeltaEnergy = enemyEnergy - e.getEnergy();
		if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= MAX_BULLET_POWER) {
			Wave enemyWave = new Wave(currentEnemyLocation, enemyDeltaEnergy,
					movementStartBearing, movementBearingDirection);
			enemyWave.observations = realMovementFactors;
			enemyWave.obs = new double[] { 0, enemyDistance, prevRobotVelocity - lastVelocity, lastVelocity,
					wallSmooth(myLocation, currentEnemyLocation, robotBearingDirection),
					wallSmooth(currentEnemyLocation, myLocation, robotBearingDirection), robotTSVC };
			enemyWave.distanceFromGun = 2 * bulletVelocity(enemyDeltaEnergy);
			addCustomEvent(enemyWave);
		}
		prevRobotVelocity = lastVelocity;
		enemyEnergy = e.getEnergy();

		// <movement>
		int forwardDanger = 0, reverseDanger = 0;
		try {
			int bin = enemyWave.hitBin(new Point2D.Double(myX, myY));
			enemyWave.query();
			forwardDanger = (int) scores[bin];
			reverseDanger = (int) scores[2 * MIDDLE_FACTOR - bin];
		} catch (Exception ex) {
		}
		if (reverseDanger < forwardDanger) {
			direction = -direction;
		}
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
		double moveAngle = enemyAbsoluteBearing - direction * 1.5707963267948966;
		while (!fieldRectangle.contains(project(moveAngle, 160)))
			moveAngle += direction * 0.01;
		double angle;
		setAhead(Math.cos(angle = moveAngle - getHeadingRadians()) * 200);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun>
		double enemyVelocity = e.getVelocity();
		int velocityIndex = (int) Math.abs(enemyVelocity);

		if (enemyVelocity != 0) {
			enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
					enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		}
		Wave wave = new Wave(myLocation, Math.min(getEnergy() / 2, 2),
				enemyAbsoluteBearing, enemyBearingDirection);
		wave.observations = aimFactors;
			if (prevEnemyVelocity != enemyVelocity) enemyTSVC = 0; else enemyTSVC++;
			wave.obs = new double[] { 0, enemyDistance, prevEnemyVelocity - enemyVelocity, enemyVelocity,
					wallSmooth(currentEnemyLocation, myLocation, enemyBearingDirection),
					wallSmooth(myLocation, currentEnemyLocation, enemyBearingDirection), enemyTSVC };
			prevEnemyVelocity = enemyVelocity;
		wave.query();

		int mostVisited = MIDDLE_FACTOR, i = -1;
		try {
			while (true) if (scores[++i] > scores[mostVisited]) {
				mostVisited = i;
			}
		} catch (Exception ex) {
		}

		setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
				wave.bearingDirection * (mostVisited - MIDDLE_FACTOR)));

		setFire(wave.bulletPower);
		addCustomEvent(wave);
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
		enemyWave = null;
	}

	public void onHitByBullet(HitByBulletEvent e) {
		try {
			enemyWave.record(new Point2D.Double(myX, myY));
		} catch (Exception ex) {
		}
	}

	static double bulletVelocity(double power) {
		return 20 - 3 * power;
	}

	static Point2D project(double angle, double length) {
		return new Point2D.Double(myX + Math.sin(angle) * length, myY + Math.cos(angle) * length);
	}

	static Point2D project(Point2D source, double angle, double length) {
		return new Point2D.Double(source.getX() + Math.sin(angle) * length,
				source.getY() + Math.cos(angle) * length);
	}

	static double wallSmooth(Point2D from, Point2D toward, double direction) {
		double w = 0;
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
		while (w < 100 && !fieldRectangle.contains(project(from,
				absoluteBearing(from, toward) - direction * (1.8207963267948966 - w++ / 100.0), 150)))
			;
		return w;
	}

	static double absoluteBearing(Point2D source, Point2D target) {
		return Math.atan2(target.getX() - source.getX(), target.getY() - source.getY());
	}

	static class Wave extends Condition {
		double bulletPower;
		Point2D gunLocation;
		double startBearing;
		double bearingDirection;
		double[] obs;
		ArrayList<double[]> observations;
		double distanceFromGun;

		Wave(Point2D gl, double bp, double sb, double bd) {
			gunLocation = gl; bulletPower = bp; startBearing = sb; bearingDirection = bd;
		}

		int hitBin(Point2D target) {
			return (int) Math.round(Utils.normalRelativeAngle(
					absoluteBearing(gunLocation, target) - startBearing) / bearingDirection + MIDDLE_FACTOR);
		}

		void record(Point2D target) {
			obs[0] = hitBin(target);
			observations.add(obs);
		}

		void query() {
			scores = new double[FACTORS];
			String w = observations == realMovementFactors ? SW : GW;
			try {
				for (int i = 0; ; i++) {
					double[] o = observations.get(i);
					double d = 0.01;
					for (int j = 1; j < 7; j++)
						d += Math.abs(o[j] - obs[j]) * w.charAt(j - 1);
					scores[(int) o[0]] += (w.charAt(6) + i) / (d * d);
				}
			} catch (Exception e) {
			}
		}

		public boolean test() {
			distanceFromGun += bulletVelocity(bulletPower);
			if (observations == realMovementFactors) {
				if (distanceFromGun < gunLocation.distance(myX, myY) + 50 && enemyWave == null) {
					enemyWave = this;
				}
			} else if (observations != null && distanceFromGun > gunLocation.distance(currentEnemyLocation) - 18) {
				try {
					record(currentEnemyLocation);
				} catch (Exception e) {
				}
				observations = null;
			}
			return false;
		}
	}
}
