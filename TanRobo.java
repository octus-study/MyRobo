package MyRobo;

import robocode.*;
import robocode.robotinterfaces.IBasicEvents;
import robocode.robotinterfaces.IBasicEvents2;
import robocode.robotinterfaces.IInteractiveEvents;
import robocode.util.Utils;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.DoubleStream;

import static robocode.util.Utils.normalRelativeAngleDegrees;


/**notes:
 *anti-gravity: set target as the repulsion points and also add wall-avoidance
 *
 */
//lose energy when robot hit the wall, ram target, get hit by bullet

public class TanRobo  extends AdvancedRobot implements IBasicEvents, IBasicEvents2, IInteractiveEvents {

    final double PI = Math.PI;
    private LUT lut;
    private QLearning qLearningAgent;
    private Target target;

    private boolean ifEpsilonDecrease = false;

    // The coordinates of the last scanned robot
    private RobotStatus robotStatus;


    int scannedX = Integer.MIN_VALUE;
    int scannedY = Integer.MIN_VALUE;

    private static int numStates = States.numStates;
    private static int numActions = Actions.numActions;

    private double reward = 0.0;

    private boolean movingForward; // is true when setAhead is called, set to false on setbBack


    private boolean ifFirstRun = true;

    //for LUT
    private int prevState;
    private int prevAction;

    private int crtState;
    private int crtAction;

    //for NN
    private double[] prevState_NN;
    private double[] crtState_NN;
    private static int argNumInputs = numStates + numActions;
    private static int numTrainingVector = (3*2*8*6) * numActions; //each line of lut
    private static int argNumHidden = 14;
    private static double argLearningRate = 0.05;
    private static double argMomentumRate = 0.9;
    private static double argA = 0;
    private static double argB = 1;
    private static double lowerBoundW = -0.5;
    private static double upperBoundW = 0.5;
    NN nn = new NN(argNumInputs, argNumHidden, argLearningRate, argMomentumRate, argA, argB, lowerBoundW, upperBoundW);

    public static final int SegDistance2target = 3;    //3 segmentation close:d<=200, medium:200<d<=400, far:d>400
    public static final int SegX = 8;
    public static final int SegY = 6;
    public static final int SegGunHeat = 2;



    private boolean offPolicy = true;

    private boolean foundTarget = false;


    //rewards
    private double accumReward = 0.0;
    private double rewardForWin = 10;
    private double rewardForDeath = -10;
    private boolean interReward =false;

    private int moveDirection = 1;



    //save win rate per 100 battle
    public static final String LOG_WINRATE = "./win_rate_per_hundredRound.csv";
    public static final String LOG_WINRATE_PER10 = "./win_rate_per_10Round.csv";
    private File winRatesFile;
    private File winRatesFile_per10;
    private static int[] winRateArr = new int[100000];
    private static int[] winRateArr_per10 = new int[1000000];
    //private int countB = 0;

    //save win rate per 50 round:
    public static final String LOG_WINRATE_50 = "./win_rate_per_50.csv";
    private File winRatesFile_50;
    private static int[] winRateArr_50 = new int[100000];

    //save accumReward per round
    public static final String LOG_ACCUMREWARD = "./accumReward_per_round.csv";
    private File accumRewardFile;
    private static double[] accumRewardArr = new double[10000000];


    public void run() {


        lut = new LUT();
        loadData();
        qLearningAgent = new QLearning(lut);
        target = new Target(0, 0,100000, 0, 0,0,0,0); //initializing target

        // set body colour
        robotColor();


        // every parts of the robot moves freely from the others
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        turnRadarRight(360);
        execute();

        winRatesFile = getDataFile(LOG_WINRATE);
        winRatesFile_per10 = getDataFile(LOG_WINRATE_PER10);
        winRatesFile_50 = getDataFile(LOG_WINRATE_50);
        accumRewardFile = getDataFile(LOG_ACCUMREWARD);


        //After initializing Q table, enter the Loop for each episode until terminal state

        while (true) {

            turnRadarRight(360);
            radarLockOnTarget();
            //gradually change epsilon
            //int crtRoundNum = getRoundNum();
            if(ifEpsilonDecrease)
            {
                int changePerNRounds = 1000;

                int count = getRoundNum() / changePerNRounds; // 1000, 100, and 10000 ,1000
                QLearning.epsilon = 0.9 - count/10.0 ; //round as 0.0
            }

            if(ifFirstRun) {

                //initial state
                //for LUT
                prevState = getState();
                //for NN
                //prevState_NN = getState_NN();

                //random action
                Random randomGenerator = new Random();
                prevAction = randomGenerator.nextInt(Actions.numActions);

                //take action
                takeAction(prevAction);

                crtState = prevState;
                crtAction = prevAction;

                ifFirstRun = false;


            }else {
                //get S'
                //for LUT
                crtState = getState();
                crtAction = qLearningAgent.policySelectAction(crtState);
                //for NN
                //crtState_NN = getState_NN();

                //off-policy or on-policy
                if(offPolicy) { //Q learning
                    qLearningAgent.Q_Learning(prevState,prevAction,crtState,reward);

                }
                else { //Sarsa
                    //crtAction = qLearningAgent.policySelectAction(crtState);
                    qLearningAgent.Sarsa(prevState,prevAction,crtState,crtAction,reward);
                }

                prevState = crtState; //S <- S'
                prevAction = crtAction;

                accumReward += reward;// get reward
                accumRewardArr[(getRoundNum())/50] = accumReward;
                reward = 0.0d;

                if (interReward) {
                    if (prevAction == Actions.robotFire && getGunHeat() != 0)
                        reward += -3;
                }
                takeAction(crtAction);
            }
            setTurnRadarLeftRadians(getRadarTurnRemainingRadians());
        }

    }

    //actions
    public void action_spiral() {
        avoidWalls();

        double absBearing = getHeading() + target.getTargetBearing();
        double bearingFromGun = normalRelativeAngleDegrees(absBearing - getGunHeading());
        double bearingFromRadar = normalRelativeAngleDegrees(absBearing - getRadarHeading());

        //spiral around the target 90 degrees would be circling it (parallel at all times)
        // 80 and 100 make that we move a bit closer every turn.

        if (movingForward) {  //if distance2target is decreasing, then it's moving forward, otherwise it's moving backward.
            setTurnRight(normalRelativeAngleDegrees(target.getTargetBearing() + 80));
        } else {
            setTurnRight(normalRelativeAngleDegrees(target.getTargetBearing() + 180));
        }

        if (Math.abs(bearingFromGun) <= 4) { //close enough
            setTurnGunRight(bearingFromGun);
            setTurnRadarRight(bearingFromRadar); //keep the radar focused on the target
            //myFire();
            /*if (getGunHeat() == 0 && getEnergy() > .2) {
                fire(Math.min(4.5 - Math.abs(bearingFromGun) / 2 - target.getTargetDistance() / 250, getEnergy() - .1));
            }*/
        } else {//not close enough, just set the gun to turn
            setTurnGunRight(bearingFromGun);
            setTurnRadarRight(bearingFromGun);
        }




    }

    public void action_advance() { //like super-tracker
        avoidWalls();
        double absBearing = getHeadingRadians() + target.getTargetBearingRadians(); // absolute bearing of target
        double lastVel = target.getTargetVelocity() * Math.sin(target.getTargetHeadingRadians() - absBearing); // later velocity of target
        double gunTurnAmt; // amount to turn the gun

        setTurnGunLeftRadians(getRadarTurnRemainingRadians()); //lock on the radar

        if (Math.random() > 0.9) {
            setMaxVelocity((12 * Math.random()) + 12); //randomly change speed
        }

        gunTurnAmt = robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / 22); //amount to turn our gun, lead just a little bit
        setTurnGunRightRadians(gunTurnAmt); //turn the gun
        setTurnRightRadians(robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / getVelocity())); //drive towards the enemies predicted future location
        setAhead((target.getTargetDistance() - 140) * moveDirection);
       // myFire();

        if (target.getTargetDistance() > 150) {
            gunTurnAmt = robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / 22); //amount to turn our gun, lead just a little bit
            setTurnGunRightRadians(gunTurnAmt); //turn the gun
            setTurnRightRadians(robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / getVelocity())); //drive towards the enemies predicted future location
            setAhead((target.getTargetDistance() - 140) * moveDirection);
            myFire();
        } else {
            gunTurnAmt = robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / 15);
            setTurnRightRadians(gunTurnAmt);
            setTurnLeft(-90 - target.getTargetBearing()); ////turn perpendicular to the enemy
            setAhead((target.getTargetDistance() - 140) * moveDirection); //move forward
            myFire();
        }

    }

    public void action_advance1() {
        avoidWalls();
        double absBearing = getHeadingRadians() + target.getTargetBearingRadians(); // absolute bearing of target
        double lastVel = target.getTargetVelocity() * Math.sin(target.getTargetHeadingRadians() - absBearing); // later velocity of target
        double gunTurnAmt; // amount to turn the gun

        setTurnGunLeftRadians(getRadarTurnRemainingRadians()); //lock on the radar

        if (Math.random() > 0.9) {
            setMaxVelocity((12 * Math.random()) + 12); //randomly change speed
        }

        gunTurnAmt = robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / 15);
        setTurnRightRadians(gunTurnAmt);
        setTurnLeft(-90 - target.getTargetBearing()); ////turn perpendicular to the enemy
        setAhead((target.getTargetDistance() - 140) * moveDirection); //move forward
        //myFire();

    }

    public void action_retreat() {
        avoidWalls();
        double absBearing = getHeadingRadians() + target.getTargetBearingRadians(); // absolute bearing of target
        double lastVel = target.getTargetVelocity() * Math.sin(target.getTargetHeadingRadians() - absBearing); // later velocity of target
        double gunTurnAmt; // amount to turn the gun

        setTurnGunLeftRadians(getRadarTurnRemainingRadians()); //lock on the radar

        if (Math.random() > 0.9) {
            setMaxVelocity((12 * Math.random()) + 12); //randomly change speed
        }

        gunTurnAmt = robocode.util.Utils.normalRelativeAngle(absBearing - getHeadingRadians() + lastVel / 15);
        setTurnRightRadians(gunTurnAmt);
        setTurnLeft(-90 - target.getTargetBearing()); ////turn perpendicular to the enemy
        setAhead((target.getTargetDistance() - 140) * (-moveDirection)); //move backward
        //myFire();

    }


    private int getState() {
        double energy = getEnergy();
        int energy_ = States.energyAfterSeg(energy);
        double energyTarget =target.getTargetEnergy();
        int energyTargt_ = States.energyAfterSeg(energyTarget);

        int x_ = States.xAfterSeg(getX());
        int y_ = States.yAfterSeg(getY());


        double x = getX();
        double y = getY();
        int generalLocation_ = States.generalLocationAfterSeg(x,y);
        int generalLocationTarget_ = States.generalLocationAfterSeg(target.getTargetX(), target.getTargetY() );


        double distance = target.getTargetDistance();
        int distance_ = States.distanceAfterSeg(distance);
        //int targetBearingRadians_= States.targetBearingAfterSeg(target.getTargetBearingRadians());
        int absBearingRadians_ = States.absBearingRadiansAfterSeg(target.getTargetHeadingRadians()+ target.getTargetBearingRadians());
       // int heading_ = States.headingAfterSeg(getHeading());
        int gunHeat_ = States.gunHeatAfterSeg(getGunHeat());

        int state = States.getIndexForStates(distance_,gunHeat_);
        return state;

    }


    @Override
    public void onStatus(StatusEvent statusEvent) {
        this.robotStatus = statusEvent.getStatus();

    }

    @Override
    public void onBulletHit(BulletHitEvent bulletHitEvent) {
        double change = bulletHitEvent.getBullet().getPower()*3;
        //System.out.println("Bullet Hit: " + change);
        if (interReward) {
            reward += change;
        }

    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent bulletHitBulletEvent) {

    }

    @Override
    public void onBulletMissed(BulletMissedEvent bulletMissedEvent) {

        double change = -bulletMissedEvent.getBullet().getPower();
       // System.out.println("Bullet Missed: " + change);
        if (interReward) reward += change;

        //but I need to track the bullet after the fire. avoiding delay rewards for firing
       //if we set negative reward to this, it might cause the robot to avoid firing

    }

    @Override
    public void onDeath(DeathEvent deathEvent) { // robot dead

       // System.out.println("l");
        reward += rewardForDeath;
        saveData();



    }

    @Override
    public void onHitByBullet(HitByBulletEvent hitByBulletEvent) {
        double power = hitByBulletEvent.getBullet().getPower();
        double change = -3*power;
        //System.out.println("Hit By Bullet: " + change);
        if (interReward) reward += change;


    }

    //This method is called when your robot collides with another robot.
    @Override
    public void onHitRobot(HitRobotEvent hitRobotEvent) {
        /*double change = -6.0;
        System.out.println("Hit Robot: " + change);
        if (interReward) reward += change;*/

        double change = -3.0;
        // System.out.println("Hit Wall: " + change);
        if (interReward) reward += change;
    }

    @Override
    public void onHitWall(HitWallEvent hitWallEvent) {
        moveDirection = -moveDirection; // reverse direction upon hitting a wall

        double change = -3.0;
       // System.out.println("Hit Wall: " + change);
        if (interReward) reward += change;

    }

    @Override
    public void onScannedRobot(ScannedRobotEvent scannedRobotEvent) {
        foundTarget = true;



        //absBearing = scannedRobotEvent.getBearingRadians() + getHeadingRadians();

        //get state: distance between robot and target
        //distance2target = scannedRobotEvent.getDistance();
        //seg4Distance = States.distanceAfterSeg(distance2target);

        target.setTargetBearing(scannedRobotEvent.getBearing());
        target.setTargetBearingRadians(scannedRobotEvent.getBearingRadians());
        target.setTargetDistance(scannedRobotEvent.getDistance());
        target.setTargetVelocity(scannedRobotEvent.getVelocity());
        target.setTargetHeadingRadians(scannedRobotEvent.getHeadingRadians());
        target.setTargetEnergy(scannedRobotEvent.getEnergy());

        //get the coordinates of enemy;
        double angle = Math.toRadians((getHeading() + scannedRobotEvent.getBearing())%360);

        // Calculate the coordinates of the robot
        scannedX = (int)(getX() + Math.sin(angle) * scannedRobotEvent.getDistance());
        scannedY = (int)(getY() + Math.cos(angle) * scannedRobotEvent.getDistance());

        target.setTargetX(scannedX);
        target.setTargetY(scannedY);



        radarLockOnTarget();
        //avoidWalls();

        // my robot is strong with it even before learning
    /*    double absBearing = getHeading() + target.getTargetBearing();
        double bearingFromGun = normalRelativeAngleDegrees(absBearing - getGunHeading());
        if (getGunHeat() == 0 && getEnergy() > .2&&Math.abs(getGunTurnRemaining()) < 10) {
            double firePower = Math.min(4.5 - Math.abs(bearingFromGun) / 2 - target.getTargetDistance() / 250, getEnergy() - .1);
            if(firePower<=1)
                setBulletColor(Color.BLUE);
            else if(firePower<=2)
                setBulletColor(Color.orange);
            else
                setBulletColor(Color.red);

            fire(firePower);
        }*/




    }

    //target dead
    @Override
    public void onRobotDeath(RobotDeathEvent robotDeathEvent) {
        target.setTargetDistance(10000);
        //numTotalGames++;

        //if (interReward) reward += 50;


    }

    @Override
    public void onWin(WinEvent winEvent) {
        System.out.println("Win!!!");
        winRateArr[(getRoundNum() - 1) / 100]++;
        winRateArr_50[(getRoundNum() - 1) / 50]++;
        winRateArr_per10[(getRoundNum() - 1) / 10]++;


        reward += rewardForWin;

        saveData();
       // int winningTag = 1;

/*        PrintStream w = null;
        try {
            w = new PrintStream(new RobocodeFileOutputStream(getDataFile("battle_history.dat").getAbsolutePath(), true));
            w.println((accumReward + " \t" + getRoundNum() + " \t" + winningTag + " \t" + QLearning.epsilon));
            if (w.checkError())
                System.out.println("Could not save the data!");
            w.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        finally {
            try {
                if (w != null)
                    w.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }*/



    }

    public void myFire() {
        //if robot hasn't found the target, turn the radar around for scanning
        foundTarget = false;
        while (!foundTarget) {
            setTurnRadarLeft(360);
            execute();
        }

       // found target
            // We check gun heat here, because calling fire()
            // uses a turn, which could cause us to lose track
            // of the other robot.

            // The close the enmy robot, the bigger the bullet.
            // The more precisely aimed, the bigger the bullet.
            // Don't fire us into disability, always save .1
        double absBearing = getHeading() + target.getTargetBearing();
        double bearingFromGun = normalRelativeAngleDegrees(absBearing - getGunHeading());
        if (getGunHeat() == 0 && getEnergy() > .2&&Math.abs(getGunTurnRemaining()) < 10) {
            double firePower = Math.min(4.5 - Math.abs(bearingFromGun) / 2 - target.getTargetDistance() / 250, getEnergy() - .1);
            if(firePower<=1)
                setBulletColor(Color.BLUE);
            else if(firePower<=2)
                setBulletColor(Color.orange);
            else
                setBulletColor(Color.red);

            fire(firePower);


        }
           /* //the more powerful the bullet, the slower
            if (distance2target<=200)
                fire(3);
            else if(distance2target<=400)
                fire(2);
            else
                fire(1);
            //energy = getEnergy();
            //fire(Rules.MAX_BULLET_POWER);

        }*/

    }

    //load and save the Q_table
    public void loadData() {
        try {
            lut.load(getDataFile("LUT.dat"));


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveData() {
        try {
            lut.save(getDataFile("LUT.dat"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }




    //interactive with keys and mouse

    @Override
    public void onKeyPressed(KeyEvent keyEvent) {
        //boolean ifFirstRound = true;

        switch (keyEvent.getKeyCode()) {

            //Typed 0-9: set exploration rate to 0-0.9
            case KeyEvent.VK_0:

                QLearning.setEpsilon(0.0);
                System.out.println("set exploration rate to 0.0");
                robotColor();
                break;

            case KeyEvent.VK_1:
                QLearning.setEpsilon(0.1);
                System.out.println("set exploration rate to 0.1");
                robotColor();
                break;

            case KeyEvent.VK_2:
                QLearning.setEpsilon(0.2);
                System.out.println("set exploration rate to 0.2");
                robotColor();
                break;

            case KeyEvent.VK_3:
                QLearning.setEpsilon(0.3);
                System.out.println("set exploration rate to 0.3");
                robotColor();
                break;

            case KeyEvent.VK_4:
                QLearning.setEpsilon(0.4);
                System.out.println("set exploration rate to 0.4");
                robotColor();
                break;

            case KeyEvent.VK_5:
                QLearning.setEpsilon(0.5);
                System.out.println("set exploration rate to 0.5");
                robotColor();
                break;

            case KeyEvent.VK_6:
                QLearning.setEpsilon(0.6);
                System.out.println("set exploration rate to 0.6");
                robotColor();
                break;

            case KeyEvent.VK_7:
                QLearning.setEpsilon(0.7);
                System.out.println("set exploration rate to 0.7");
                robotColor();
                break;

            case KeyEvent.VK_8:
                QLearning.setEpsilon(0.8);
                System.out.println("set exploration rate to 0.8");
                robotColor();
                break;

            case KeyEvent.VK_9:
                QLearning.setEpsilon(0.9);
                System.out.println("set exploration rate to 0.9");
                robotColor();
                break;

            //type O turn on InterReward(default)
            case KeyEvent.VK_O:
                interReward = true;
                System.out.println("turn on InterReward");
                break;

            //type F turn off InterReward
            case KeyEvent.VK_F:
                interReward = false;
                System.out.println("turn off InterReward");
                break;

            case KeyEvent.VK_E:
                System.out.println("current epsilon = " + QLearning.epsilon + "\n");
                break;

            case KeyEvent.VK_W:
                System.out.println("win rates for last 100 rounds: " + winRateArr[((getRoundNum() - 1) / 100) - 1]);
                break;

            case KeyEvent.VK_A:
                System.out.println("curt round accumReward: " + accumRewardArr[(getRoundNum())]);
                break;

            case KeyEvent.VK_L:
                System.out.println("last round accumReward: " + accumRewardArr[(getRoundNum() - 1)]);
                break;

            case KeyEvent.VK_T:
                System.out.println("win rates for last 10 rounds: " + winRateArr[((getRoundNum() - 1) / 10) - 1]);
                break;


            default:
                /*QLearning.setEpsilon(0.9);
                interReward = true;
                robotColor();*/

                System.out.println("[default setting] exploration rate: 0.9\n");
                System.out.println("[default setting] interReward on\n");
                System.out.println("Please press 0-9 set exploration rate to 0-0.9\n");
                System.out.println("Please press O  to turn on interReward\n");
                System.out.println("Please press F  to turn off interReward\n");
                break;

        }

    }

    @Override
    public void onKeyReleased(KeyEvent keyEvent) {

    }

    //This method is called
    // when a key has been typed (pressed and released).
    @Override
    public void onKeyTyped(KeyEvent keyEvent) {
       /* switch (keyEvent.getKeyCode()) {
            //Typed 0-9: set exploration rate to 0-0.9
            case KeyEvent.VK_0:

                QLearning.setEpsilon(0.0);
                System.out.println("set exploration rate to 0.0");
                break;

            case KeyEvent.VK_1:
                QLearning.setEpsilon(0.1);
                System.out.println("set exploration rate to 0.1");
                break;

            case KeyEvent.VK_2:
                QLearning.setEpsilon(0.2);
                System.out.println("set exploration rate to 0.2");
                break;

            case KeyEvent.VK_3:
                QLearning.setEpsilon(0.3);
                System.out.println("set exploration rate to 0.3");
                break;

            case KeyEvent.VK_4:
                QLearning.setEpsilon(0.4);
                System.out.println("set exploration rate to 0.4");
                break;

            case KeyEvent.VK_5:
                QLearning.setEpsilon(0.5);
                System.out.println("set exploration rate to 0.5");
                break;

            case KeyEvent.VK_6:
                QLearning.setEpsilon(0.6);
                System.out.println("set exploration rate to 0.6");
                break;

            case KeyEvent.VK_7:
                QLearning.setEpsilon(0.7);
                System.out.println("set exploration rate to 0.7");
                break;

            case KeyEvent.VK_8:
                QLearning.setEpsilon(0.8);
                System.out.println("set exploration rate to 0.8");
                break;

            case KeyEvent.VK_9:
                QLearning.setEpsilon(0.9);
                System.out.println("set exploration rate to 0.9");
                break;

           //type O turn on InterReward(default)
            case KeyEvent.VK_O:
                interReward = true;
                System.out.println("turn on InterReward");
                break;

           //type F turn off InterReward
            case KeyEvent.VK_F:
                interReward = false;
                System.out.println("turn off InterReward");
                break;

            default:
                QLearning.setEpsilon(0.9);
                interReward = true;

                System.out.println("[default setting] exploration rate: 0.9\n");
                System.out.println("[default setting] interReward on\n");
                System.out.println("Please press 0-9 set exploration rate to 0-0.9\n");
                System.out.println("Please press O  to turn on interReward\n");
                System.out.println("Please press F  to turn off interReward\n");
                break;

        }*/


    }

    // This method is called
    // when a mouse button has been clicked (pressed and released).
    @Override
    public void onMouseClicked(MouseEvent mouseEvent) {

    }

    @Override
    public void onMouseEntered(MouseEvent mouseEvent) {

    }

    @Override
    public void onMouseExited(MouseEvent mouseEvent) {

    }

    @Override
    public void onMousePressed(MouseEvent mouseEvent) {

    }

    @Override
    public void onMouseReleased(MouseEvent mouseEvent) {

    }

    @Override
    public void onMouseMoved(MouseEvent mouseEvent) {

    }

    @Override
    public void onMouseDragged(MouseEvent mouseEvent) {

    }

    @Override
    public void onMouseWheelMoved(MouseWheelEvent mouseWheelEvent) {

    }


    private void radarLockOnTarget() {
        //get radar lock on the target
        double radarTurn = getHeadingRadians() + target.getTargetBearingRadians() - getRadarHeadingRadians();
        setTurnRadarRightRadians(Utils.normalRelativeAngle(radarTurn));

        //set gun toward enemy
        double gunTurn = getHeadingRadians() + target.getTargetBearingRadians() - getGunHeadingRadians();
        setTurnGunRightRadians(Utils.normalRelativeAngle(gunTurn));
    }

    private void robotColor () {
        if(QLearning.epsilon == 0.0)
        {
            setColors(Color.blue, Color.red, Color.black);
        }
        else if(QLearning.epsilon == 0.9)
        {
            setColors(Color.red, Color.red, Color.black);
        }
        else
            setColors(Color.yellow, Color.red, Color.black);
    }

    @Override
    public void onBattleEnded(BattleEndedEvent battleEndedEvent) {
       /* int roundNum = getRoundNum();
        int per100Battle = roundNum%100*/
       //win rates per 100 round
        System.out.println("end !!!!!!!!!!!!!!!end!!!!!!!!!!!!1");
        /*saveStats_win(winRatesFile,getRoundNum(),winRateArr);
        saveStats_accumAward(accumRewardFile,getRoundNum(),accumRewardArr);*/
       /* saveStats_win(getRoundNum(),winRateArr);
        saveStats_accumAward(getRoundNum(),accumRewardArr);*/
        saveStats_win_perHundred(winRatesFile);
        saveStats_win_per10(winRatesFile_per10);
        saveStats_win_per50(winRatesFile_50);
        saveStats_award(accumRewardFile);

    }

    public void onRoundEnded(RoundEndedEvent e) {

        saveStats_win_perHundred(winRatesFile);
        saveStats_win_per10(winRatesFile_per10);
        saveStats_win_per50(winRatesFile_50);
        saveStats_award(accumRewardFile);

    }

    private void saveStats_win_per50 (File statsFile) {

        int i;

        try
        {
            RobocodeFileOutputStream fileOut = new RobocodeFileOutputStream(statsFile);
            PrintStream out = new PrintStream(new BufferedOutputStream(fileOut));
            out.format("100 Rounds, Wins,\n");
            for (i = 0; i < getRoundNum()/50; i++)
            {
                out.format("%d, %f,\n", i + 1, (winRateArr_50[i])/50.0);
            }

            out.close();
            fileOut.close();
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
        }

    }


    private void saveStats_win_perHundred(File statsFile) {
        int i;

        try
        {
            RobocodeFileOutputStream fileOut = new RobocodeFileOutputStream(statsFile);
            PrintStream out = new PrintStream(new BufferedOutputStream(fileOut));
            out.format("100 Rounds, Wins,\n");
            for (i = 0; i < getRoundNum()/100; i++)
            {
                out.format("%d, %f,\n", i + 1, (winRateArr[i])/100.0);
            }

            out.close();
            fileOut.close();
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
        }
    }


    private void saveStats_award(File statsFile) {
        int i;

        try
        {
            RobocodeFileOutputStream fileOut = new RobocodeFileOutputStream(statsFile);
            PrintStream out = new PrintStream(new BufferedOutputStream(fileOut));
            out.format("per Round, AccumReward,\n");
            for (i = 0; i < getRoundNum()/50; i++)
            {
                out.format("%d, %f,\n", i + 1, accumRewardArr[i]/50.0);
            }

            out.close();
            fileOut.close();
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
        }
    }

    private void saveStats_win_per10(File statsFile) {
        int i;

        try
        {
            RobocodeFileOutputStream fileOut = new RobocodeFileOutputStream(statsFile);
            PrintStream out = new PrintStream(new BufferedOutputStream(fileOut));
            out.format("perRounds, Wins,\n");
            for (i = 0; i < getRoundNum()/10; i++)
            {
                out.format("%d, %f,\n", i + 1,( winRateArr_per10[i])/10.0);
            }

            out.close();
            fileOut.close();
        }
        catch (IOException exception)
        {
            exception.printStackTrace();
        }
    }






    //todo
    // 1.Add antiGravPoint to avoid wall and enemy
    // 2. Improve the performance of the robot

    public void avoidWalls() {
        double xforce = 0;
        double yforce = 0;
        GravPoint p;
        /**The following four lines add wall avoidance.  They will only
         affect us if the bot is close to the walls due to the
         force from the walls decreasing at a power 3.**/
        xforce += 5000/Math.pow(getRange(getX(),
                getY(), getBattleFieldWidth(), getY()), 3);
        xforce -= 5000/Math.pow(getRange(getX(),
                getY(), 0, getY()), 3);
        yforce += 5000/Math.pow(getRange(getX(),
                getY(), getX(), getBattleFieldHeight()), 3);
        yforce -= 5000/Math.pow(getRange(getX(),
                getY(), getX(), 0), 3);

        //Move in the direction of our resolved force.
        goTo(getX()-xforce,getY()-yforce);
    }


    public void goTo(double x, double y){
        double dist = 20;
        double angle = Math.toDegrees(absBearing(getX(),getY(),x,y));
        double r = turnTo(angle);
        setAhead(dist * r);

    }

    //gets the absolute bearing between to x,y coordinates
    public double absBearing(double x1, double y1, double x2, double y2 )
    {
        double xo = x2-x1;
        double yo = y2-y1;
        double h = getRange( x1,y1, x2,y2 );
        if( xo > 0 && yo > 0 )
        {
            return Math.asin( xo / h );
        }
        if( xo > 0 && yo < 0 )
        {
            return Math.PI - Math.asin( xo / h );
        }
        if( xo < 0 && yo < 0 )
        {
            return Math.PI + Math.asin( -xo / h );
        }
        if( xo < 0 && yo > 0 )
        {
            return 2.0*Math.PI - Math.asin( -xo / h );
        }
        return 0;
    }

    public double getRange( double x1,double y1, double x2,double y2 )
    {
        double xo = x2-x1;
        double yo = y2-y1;
        double h = Math.sqrt( xo*xo + yo*yo );
        return h;
    }

    /**Turns the shortest angle possible to come to a heading, then returns the direction the
     the bot needs to move in.**/
    int turnTo(double angle) {
        double ang;
        int dir;
        ang = normaliseBearing(getHeading() - angle);
        if (ang > 90) {
            ang -= 180;
            dir = -1;
        }
        else if (ang < -90) {
            ang += 180;
            dir = -1;
        }
        else {
            dir = 1;
        }
        setTurnLeft(ang);
        return dir;
    }

    double normaliseBearing(double ang) {
        if (ang > PI)
            ang -= 2*PI;
        if (ang < -PI)
            ang += 2*PI;
        return ang;
    }


    private void takeAction (int action) {

        //execute action
        switch (action) {

            /**
             *  The setXXX() methods tells Robocode
             *  that this in an "asynchroneous" action. The other
             *  methods like turnRadarLeft(360).
             *  Will execute the command, and wait for it to finish.
             */
               /* case Actions.robotSpiral:
                    System.out.println("take Action: spiral ");
                    action_spiral();
                    break;

                case Actions.robotTrack:
                    System.out.println("take Action:advance ");
                    action_advance();
                    break;*/

                /*case Actions.robotTrack1:
                    System.out.println("take Action: advance1 ");
                    action_advance1();
                    break;

                case Actions.robotRetreat:
                    System.out.println("take Action: retreat");
                    action_retreat();
                    break;*/

            case Actions.robotAhead:
                radarLockOnTarget();
                System.out.println("take Action: ahead ");
                setAhead(Actions.RobotMoveDistance);
                //myFire();
                movingForward = true;

                break;

            case Actions.robotBack:
                radarLockOnTarget();
                System.out.println("take Action: back ");
                setBack(Actions.RobotMoveDistance);
                //myFire();
                movingForward = false;
                break;

            case Actions.robotTurnLeft:
                radarLockOnTarget();
                System.out.println("take Action: turn left ");
                setTurnLeft(Actions.RobotTurnDegree);
                //setAhead(Actions.RobotMoveDistance);
                //ahead(Actions.RobotMoveDistance);
                //myFire();
                break;

            case Actions.robotTurnRight:
                radarLockOnTarget();
                System.out.println("take Action: turn right ");
                setTurnRight(Actions.RobotTurnDegree);
                //setAhead(Actions.RobotMoveDistance);
                //ahead(Actions.RobotMoveDistance);
                //myFire();
                break;

            case Actions.robotSpin:
                radarLockOnTarget();
                System.out.println("take Action: spin ");
                setTurnRight(target.getTargetBearing()+Actions.RobotTurnDegree_L);
                setAhead(Actions.RobotMoveDistance_L);

              /*  case Actions.robotAhead_L:
                    // radarLockOnTarget();
                    System.out.println("take Action: ahead L");
                    setAhead(Actions.RobotMoveDistance_L);

                    myFire();
                    movingForward = true;

                    break;

                case Actions.robotBack_L:
                    // radarLockOnTarget();
                    System.out.println("take Action: back L ");
                    setBack(Actions.RobotMoveDistance_L);

                    myFire();
                    movingForward = false;
                   //System.out.println("take Action: back L ");
                    break;

                case Actions.robotTurnLeft_L:
                    // radarLockOnTarget();
                    System.out.println("take Action: turn left L ");
                    setTurnLeft(target.getTargetBearing()+Actions.RobotTurnDegree_L);
                    ahead(Actions.RobotMoveDistance_L);

                    myFire();
                    break;

                case Actions.robotTurnRight_L:
                    // radarLockOnTarget();
                    System.out.println("take Action: turn right L");
                    setTurnRight(target.getTargetBearing()+Actions.RobotTurnDegree_L);
                    ahead(Actions.RobotMoveDistance_L);
                    System.out.println("take Action: turn right L");
                    myFire();
                    break;*/

               /* case Actions.rotate1:
                    System.out.println("take Action: rotate 1");
                    setTurnRight(target.getTargetBearing()+Actions.RobotTurnDegree_L);
                    ahead(target.getTargetDistance());


                */


                case Actions.robotFire:
                    System.out.println("take Action: Fire! ");
                    //radarLockOnTarget();
                    myFire();
                    break;


            default: // cause robot doesn't move at all!
                System.out.println("Action Not Found");
                break;

        }
        //to avoid delay rewards for firing.

        execute();
        System.out.println("Action ends");
    }

    private double[] getState_NN() {//no quantization
        //for states in NN
        double state_NN[] = new double[4]; //distance gunHeat X, Y

        //input with normalization x_ = (x - min)/(max - min) (0~1)
        //and scale it to -1 ~ 1: x__ = 2/x_ - 1;
        state_NN[1] = 2 / (target.getTargetDistance()/1000.0) - 1;
        state_NN[2] = 2 / (getGunHeat()/1.0) - 1;
        state_NN[3] = 2 / (getX()/800.0) - 1;
        state_NN[4] = 2 / (getY()/600.0) - 1;

        return state_NN;
    }

    private double[] action_NN(int action) { //for action{-1 -1 -1 -1 -1 1} 1 for chosen action, -1 for non-chosen action
        double[] action_NN = new double[numActions];
        Arrays.fill(action_NN, -1.0);
        //set chosen action to -1
        action_NN[action] = 1.0;
        return action_NN;
    }

    private double getQValue_NN(double[] state_NN, double[] action_NN, double desiredQ) {

        //combine state and action to form input vector
        double[] input_NN = DoubleStream.concat(Arrays.stream(state_NN), Arrays.stream(action_NN)).toArray();

        //train in NN and get output: Q value
        double output_NN = nn.outputFor(input_NN);
        nn.train(input_NN, desiredQ);
        output_NN = nn.outputFor(input_NN);

        return output_NN;

    }

 /*   //Training NN with LUT
    public static void main(String[] args) throws FileNotFoundException {
        TanRobo robo = new TanRobo();
        LUT lut_NN = new LUT();
        File weights_file = new File("./TanRobo.data.weights.txt");
        robo.loadData();

        List<double[]> inputs = new ArrayList<double[]>();
        List<Double> output = new ArrayList<Double>();

        //initialize input vector
        for (int a = 0; a < SegDistance2target; a++) {
            for (int b = 0; b < SegGunHeat; b++)
                for (int c = 0; c < SegX; c++)
                    for (int d = 0; d < SegY; d++)
                        for (int action = 0; action < numActions; action++) {
                            double[] newInput = {
                                    2.0 * (double) a / (double) (SegDistance2target - 1) - 1,
                                    2.0 * (double) b / (double) (SegGunHeat - 1) - 1,
                                    2.0 * (double) c / (double) (SegX - 1) - 1,
                                    2.0 * (double) d / (double) (SegY - 1) - 1
                            };

                            double[] action_NN = new double[numActions];
                            Arrays.fill(action_NN, -1.0);
                            action_NN[action] = 1.0;

                            //combine state and action
                            newInput = DoubleStream.concat(Arrays.stream(newInput), Arrays.stream(action_NN)).toArray();

                            //todo confusing here
                            int crtState = States.Mapping[a][b][c][d];
                            double newOutput = (lut_NN.qTable[crtState][action] - -10) / 20;
                            if (lut_NN.qTable[crtState][action] != 0) {
                                inputs.add(newInput);
                                output.add((lut_NN.qTable[crtState][action] - -10) / 20);
                            }


                        }
        }

        int count  = 0;
        int convergeTime = 0;
        int iterationTime = 10;
        double totalErr = 0;
        double sqrtErr = 999999; //root-mean-square error

        //Test Constants
        double inputVector[][] = new double[inputs.size()][argNumInputs];
        double outputVector[] =  new double[inputs.size()];
        inputVector = inputs.toArray(inputVector);

        for (int i = 0; i < output.size(); i++) {
            outputVector[i] = output.get(i);
        }

        for (int k = 0; k < iterationTime; k++) {

            int max_epochs = 10000;
            for (int i = 0; i <  max_epochs; i++) {
                totalErr = 0;
                for (int j = 0; j < inputVector.length; j++) {
                    totalErr += nn.train(inputVector[j], outputVector[j]);

                }
                sqrtErr = Math.sqrt(totalErr);

                System.out.println(i + ";" + sqrtErr);
                nn.save(weights_file);
            }

            *//*if (sqrtErr < 0.05) {
                convergeTime++;
                break;
            }*//*
        }







    }*/

}

