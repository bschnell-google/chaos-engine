package com.gemalto.chaos.constants;

public abstract class AwsEC2Constants {
    public static final int AWS_PENDING_CODE = 0;
    public static final int AWS_RUNNING_CODE = 16;
    public static final int AWS_SHUTTING_DOWN_CODE = 32;
    public static final int AWS_STOPPING_CODE = 64;
    public static final int AWS_TERMINATED_CODE = 48;
    public static final int AWS_STOPPED_CODE = 80;
    public static final String EC2_DEFAULT_CHAOS_SECURITY_GROUP_NAME = "ChaosEngine Security Group";
    public static final String EC2_DEFAULT_CHAOS_SECURITY_GROUP_DESCRIPTION = "(DO NOT USE) Security Group used by Chaos Engine to simulate random network failures.";
    public static final int AWS_EC2_HARD_REBOOT_TIMER_MINUTES = 4;

    private AwsEC2Constants () {
    }

    public static int[] getAwsUnhealthyCodes () {
        return new int[]{ AWS_PENDING_CODE, AWS_SHUTTING_DOWN_CODE, AWS_STOPPING_CODE, AWS_STOPPED_CODE, AWS_STOPPED_CODE };
    }
}
