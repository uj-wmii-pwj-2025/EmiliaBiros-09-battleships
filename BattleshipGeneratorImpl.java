import java.util.*;

public class BattleshipGeneratorImpl implements BattleshipGenerator {

    private static final int BOARD_SIZE = 10;
    private static final char WATER = '.';
    private static final char SHIP = '#';
    private static final int[] SHIP_SIZES = {4, 3, 3, 2, 2, 2, 1, 1, 1, 1};
    private static final int ATTEMPTS = 1000;
    private static final int MAX_SHIP_PLACEMENT_ATTEMPTS = 100;

    private static final Direction[] ALL_DIRECTIONS = {
            new Direction(-1, 0), new Direction(1, 0),
            new Direction(0, -1), new Direction(0, 1),
            new Direction(-1, -1), new Direction(1, 1),
            new Direction(-1, 1), new Direction(1, -1)
    };

    private static final Direction[] ORTHOGONAL_DIRECTIONS = {
            new Direction(-1, 0), new Direction(1, 0),
            new Direction(0, -1), new Direction(0, 1)
    };

    private final Random random = new Random();
    private char[][] board;

    @Override
    public String generateMap() {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            if (tryGenerateBoard()) {
                return convertBoardToString();
            }
        }
        throw new RuntimeException("Failed to generate valid board after " + ATTEMPTS + " attempts");
    }

    private boolean tryGenerateBoard() {
        try {
            initializeBoard();
            placeAllShips();
            return true;
        } catch (ShipPlacementException e) {
            return false;
        }
    }

    private void initializeBoard() {
        board = new char[BOARD_SIZE][BOARD_SIZE];
        for (char[] row : board) {
            Arrays.fill(row, WATER);
        }
    }

    private void placeAllShips() {
        for (int shipSize : SHIP_SIZES) {
            placeShip(shipSize);
        }
    }

    private void placeShip(int shipSize) {
        for (int attempt = 0; attempt < MAX_SHIP_PLACEMENT_ATTEMPTS; attempt++) {
            if (tryPlaceShipAtRandomPosition(shipSize)) {
                return;
            }
        }
        throw new ShipPlacementException("Could not place ship of size " + shipSize);
    }

    private boolean tryPlaceShipAtRandomPosition(int shipSize) {
        Position startPosition = generateRandomPosition();
        List<Position> shipPositions = buildShipFrom(startPosition, shipSize);

        if (shipPositions != null) {
            placeShipOnBoard(shipPositions);
            return true;
        }
        return false;
    }

    private List<Position> buildShipFrom(Position start, int shipSize) {
        List<Position> positions = new ArrayList<>(shipSize);
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        return buildShipRecursively(start, shipSize, positions, visited) ? positions : null;
    }

    private boolean buildShipRecursively(Position current, int remainingSegments,
                                         List<Position> positions, boolean[][] visited) {
        if (remainingSegments == 0) {
            return true;
        }

        if (!isValidAndUnvisited(current, visited) || !isPositionAvailable(current)) {
            return false;
        }

        markPositionAsUsed(current, positions, visited);

        if (tryPlaceRemainingSegments(current, remainingSegments - 1, positions, visited)) {
            return true;
        }

        revertPosition(current, positions, visited);
        return false;
    }

    private boolean isValidAndUnvisited(Position position, boolean[][] visited) {
        return position.isValid() && !visited[position.row][position.col];
    }

    private void markPositionAsUsed(Position position, List<Position> positions, boolean[][] visited) {
        visited[position.row][position.col] = true;
        positions.add(position);
    }

    private boolean tryPlaceRemainingSegments(Position current, int remainingSegments,
                                              List<Position> positions, boolean[][] visited) {
        for (Direction direction : getShuffledOrthogonalDirections()) {
            Position next = current.move(direction);
            if (buildShipRecursively(next, remainingSegments, positions, visited)) {
                return true;
            }
        }
        return false;
    }

    private void revertPosition(Position position, List<Position> positions, boolean[][] visited) {
        visited[position.row][position.col] = false;
        positions.removeLast();
    }

    private boolean isPositionAvailable(Position position) {
        return !isOccupiedByShip(position) && !hasAdjacentShip(position);
    }

    private boolean isOccupiedByShip(Position position) {
        return board[position.row][position.col] == SHIP;
    }

    private boolean hasAdjacentShip(Position position) {
        for (Direction direction : ALL_DIRECTIONS) {
            Position adjacent = position.move(direction);
            if (adjacent.isValid() && board[adjacent.row][adjacent.col] == SHIP) {
                return true;
            }
        }
        return false;
    }

    private void placeShipOnBoard(List<Position> positions) {
        for (Position position : positions) {
            board[position.row][position.col] = SHIP;
        }
    }

    private Position generateRandomPosition() {
        return new Position(random.nextInt(BOARD_SIZE), random.nextInt(BOARD_SIZE));
    }

    private List<Direction> getShuffledOrthogonalDirections() {
        List<Direction> directions = new ArrayList<>(Arrays.asList(ORTHOGONAL_DIRECTIONS));
        Collections.shuffle(directions, random);
        return directions;
    }

    private String convertBoardToString() {
        StringBuilder result = new StringBuilder(BOARD_SIZE * BOARD_SIZE);
        for (char[] row : board) {
            for (char cell : row) {
                result.append(cell);
            }
        }
        return result.toString();
    }

    private record Position(int row, int col) {
        boolean isValid() {
            return row >= 0 && row < BOARD_SIZE && col >= 0 && col < BOARD_SIZE;
        }

        Position move(Direction direction) {
            return new Position(row + direction.rowDelta, col + direction.colDelta);
        }
    }

    private record Direction(int rowDelta, int colDelta) {
    }

    private static class ShipPlacementException extends RuntimeException {
        ShipPlacementException(String message) {
            super(message);
        }
    }
}