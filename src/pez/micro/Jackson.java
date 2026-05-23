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
	static final double BULLET_POWER = 1.9;
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
		setAdjustGunForRobotTurn(true);
		enemyEnergy = 102;
		turnRadarRightRadians(Double.POSITIVE_INFINITY);
	}

	public void onScannedRobot(ScannedRobotEvent e) {
		myX = getX();
		myY = getY();
		double enemyAbsoluteBearing = getHeadingRadians() + e.getBearingRadians();
		currentEnemyLocation = project(enemyAbsoluteBearing, e.getDistance());
		Point2D myLocation = new Point2D.Double(myX, myY);
		int movementVelocityIndex = (int) Math.abs(lastVelocity);
		double movementStartBearing = absoluteBearing(currentEnemyLocation, myLocation);
		double movementBearingDirection = Math.copySign(0.7 / MIDDLE_FACTOR,
				lastVelocity * Math.sin(getHeadingRadians() - movementStartBearing));
		lastVelocity = getVelocity();
		double enemyDeltaEnergy = enemyEnergy - e.getEnergy();
		if (enemyDeltaEnergy > 0 && enemyDeltaEnergy <= MAX_BULLET_POWER) {
			Wave enemyWave = new Wave(currentEnemyLocation, enemyDeltaEnergy,
					movementStartBearing, movementBearingDirection);
			enemyWave.surfFactors = realMovementFactors[movementVelocityIndex];
			enemyWave.distanceFromGun = 2 * bulletVelocity(enemyDeltaEnergy);
			addCustomEvent(enemyWave);
		}
		enemyEnergy = e.getEnergy();

		// <movement>
		int forwardDanger = 0, reverseDanger = 0;
		try {
			int bin = enemyWave.hitBin(new Point2D.Double(myX, myY));
			forwardDanger = enemyWave.surfFactors[bin];
			reverseDanger = enemyWave.surfFactors[2 * MIDDLE_FACTOR - bin];
		} catch (Exception ex) {
		}
		if (reverseDanger < forwardDanger) {
			direction = -direction;
		}
		Rectangle2D fieldRectangle = new Rectangle2D.Double(WALL_MARGIN, WALL_MARGIN,
				BATTLE_FIELD_WIDTH - WALL_MARGIN * 2, BATTLE_FIELD_HEIGHT - WALL_MARGIN * 2);
		double moveAngle = enemyAbsoluteBearing - direction * 1.5707963267948966;
		while (fieldRectangle.outcode(project(moveAngle, 160)) != 0)
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
		wave.factors = aimFactors[velocityIndex];

		int mostVisited = MIDDLE_FACTOR, i = -1;
		try {
			while (true) if (wave.factors[++i] > wave.factors[mostVisited]) {
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
			enemyWave.surfFactors[enemyWave.hitBin(new Point2D.Double(myX, myY))]++;
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
			} else if (factors != null && distanceFromGun > gunLocation.distance(currentEnemyLocation) - 18) {
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
