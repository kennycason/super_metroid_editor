;;; Hi-Jump Without Boots (Demo)
;;; Makes ordinary jumps use vanilla Hi-Jump velocities.

org SamusPhysicsConstants_InitialYSpeeds_Jumping
    dw $0600*!SPF/$100,$0280*!SPF/$100,$0380*!SPF/$100

org SamusPhysicsConstants_InitialYSubSpeeds_Jumping
    dw $0600*!SPF*$100,$0280*!SPF*$100,$0380*!SPF*$100