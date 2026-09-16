package com.mspgllc.iqpuchin.board

/**
 * STEP 5: pure judgement for whether an ACTIVATE press captures a QUBE.
 * Compares only logical grid coordinates -- never render/animation state
 * -- so the result is identical regardless of where a topple is currently
 * mid-animation. Kept free of any Android/UI type, same as BoardLogic/
 * QubeMotion/MarkController.
 */
class CaptureSystem {
    fun isCaptured(markedCoord: GridCoord?, qubeCoord: GridCoord): Boolean {
        return markedCoord != null && markedCoord == qubeCoord
    }
}
