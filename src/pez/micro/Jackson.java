package pez.micro;

import robocode.*;
import robocode.util.Utils;
import java.awt.geom.*;

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
	static final double BULLET_POWER = 2;
	static final double WALL_MARGIN = 18;

	static final int DISTANCE_INDEXES = 10;
	static final int VELOCITY_INDEXES = 10;
	static final int FACTORS = 37;
	static final int MIDDLE_FACTOR = (FACTORS - 1) / 2;

	static Point2D currentEnemyLocation;
	static double enemyBearingDirection;
	static double myX, myY;
	static int[][] aimFactors = new int[VELOCITY_INDEXES][FACTORS];
	static int[][] realMovementFactors = new int[VELOCITY_INDEXES][FACTORS];
	static double direction = 1;
	static double enemyEnergy;
	static double lastVelocity;
	static Wave enemyWave;

	public void run() {
		setAdjustRadarForGunTurn(true);
		turnRadarRightRadians(Double.POSITIVE_INFINITY);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		myX = getX();
		myY = getY();
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		currentEnemyLocation = project(enemyAbsoluteBearing, e.getDistance());
		Point2D myLocation = project(0, 0);
		double movementStartBearing = absoluteBearing(currentEnemyLocation, myLocation);
		double movementBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
				lastVelocity * Math.sin(getHeadingRadians() - movementStartBearing));
		double enemyDeltaEnergy = enemyEnergy - (enemyEnergy = e.getEnergy());
		if (enemyDeltaEnergy > 0) {
			Wave enemyWave = new Wave(currentEnemyLocation, enemyDeltaEnergy,
					movementStartBearing, movementBearingDirection);
			enemyWave.surfFactors = realMovementFactors[(int) Math.abs(lastVelocity)];
			enemyWave.distanceFromGun = 2 * bulletVelocity(enemyDeltaEnergy);
			addCustomEvent(enemyWave);
		}
		lastVelocity = getVelocity();

		// <movement>
		try {
			int bin;
			if ((enemyWave.surfFactors[2 * MIDDLE_FACTOR - (bin = enemyWave.hitBin(myLocation))]) < (enemyWave.surfFactors[bin])) {
				direction = -direction;
			}
		} catch (Exception ex) {
		}
		double moveAngle = enemyAbsoluteBearing - direction * 1.5707963267948966;
		while (!new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2).contains(project(moveAngle, 160)))
			moveAngle += direction * 0.01;
		double angle;
		setAhead(Math.cos(angle = moveAngle - getHeadingRadians()) * 200);
		setTurnRightRadians(Math.tan(angle));
		// </movement>

		// <gun>
		double enemyVelocity = e.getVelocity();

		enemyBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
				enemyVelocity * Math.sin(e.getHeadingRadians() - enemyAbsoluteBearing));
		Wave wave = new Wave(myLocation, BULLET_POWER,
				enemyAbsoluteBearing, enemyBearingDirection);
		int[] factors = aimFactors[(int) Math.abs(enemyVelocity)];
		wave.factors = factors;

		int mostVisited = MIDDLE_FACTOR, i = -1;
		try {
			while (true) if (factors[++i] > factors[mostVisited]) {
				mostVisited = i;
			}
		} catch (Exception ex) {
		}

		setTurnGunRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getGunHeadingRadians() +
				enemyBearingDirection * (mostVisited - MIDDLE_FACTOR)));

		setFire(wave.bulletPower);
		addCustomEvent(wave);
		// </gun>

		setTurnRadarRightRadians(Utils.normalRelativeAngle(enemyAbsoluteBearing - getRadarHeadingRadians()) * 2);
		enemyWave = null;
	}

	public void onHitByBullet(HitByBulletEvent e) {
		try {
			enemyWave.surfFactors[enemyWave.hitBin(project(0, 0))]++;
		} catch (Exception ex) {
		}
	}

	static double bulletVelocity(double power) {
		return 20 - 3 * power;
	}

	static Point2D project(double angle, double length) {
		return new Point2D.Double(myX + Math.sin(angle) * length, myY + Math.cos(angle) * length);
	}

	static double absoluteBearing(Point2D source, Point2D target) {
		return Math.atan2(target.getX() - source.getX(), target.getY() - source.getY());
	}

	static class Wave extends Condition {
		double bulletPower;
		Point2D gunLocation;
		double startBearing;
		double bearingDirection;
		int[] factors;
		int[] surfFactors;
		double distanceFromGun;

		Wave(Point2D gl, double bp, double sb, double bd) {
			gunLocation = gl; bulletPower = bp; startBearing = sb; bearingDirection = bd;
		}

		int hitBin(Point2D target) {
			return (int) Math.round(Utils.normalRelativeAngle(
					absoluteBearing(gunLocation, target) - startBearing) / bearingDirection + MIDDLE_FACTOR);
		}

		public boolean test() {
			distanceFromGun += bulletVelocity(bulletPower);
			if (surfFactors != null) {
				if (distanceFromGun < gunLocation.distance(myX, myY) + 50 && enemyWave == null) {
					enemyWave = this;
				}
			} else if (distanceFromGun > gunLocation.distance(currentEnemyLocation) - 18) {
				try {
					factors[hitBin(currentEnemyLocation)]++;
				} catch (Exception e) {
				}
				factors = null;
			}
			return false;
		}
	}
}
