package com.mspgllc.iqpuchin.board

/** Cardinal directions on the horizontal grid. */
enum class Direction(val dx: Int, val dz: Int) {
    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0);

    fun step(from: GridCoord): GridCoord = GridCoord(from.x + dx, from.z + dz)
}
