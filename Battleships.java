import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;


public class Battleships {
    private final GameConfig config;

    public Battleships(String[] args) {
        this.config = GameConfig.fromArgs(args);
    }

    public static void main(String[] args) {
        try {
            new Battleships(args).run();
        } catch (Exception e) {
            System.err.println("blad: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void run() throws IOException {
        String mapContent = MapManager.load(config.mapFile());
        GameBoard board = new GameBoard(mapContent);
        board.displayPlayerBoard();

        try (Socket socket = ConnectionManager.establish(config)) {
            socket.setSoTimeout(60000);
            GameSession session = new GameSession(socket, board, config.isServer());
            session.play();
        }
    }
}


record GameConfig(String mode, int port, String mapFile, String host) {
    boolean isServer() {
        return "server".equalsIgnoreCase(mode);
    }

    static GameConfig fromArgs(String[] args) {
        String mode = null, mapFile = null, host = null;
        int port = 0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-mode" -> mode = args[++i];
                case "-port" -> port = Integer.parseInt(args[++i]);
                case "-map" -> mapFile = args[++i];
                case "-host" -> host = args[++i];
            }
        }

        if (mode == null || port == 0 || mapFile == null) {
            System.out.println("uzycie: -mode [server|client] -port N -map file [-host host]");
            System.exit(1);
        }
        return new GameConfig(mode, port, mapFile, host);
    }
}


class MapManager {
    static String load(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (Files.exists(path)) {
            return Files.readString(path);
        }

        System.out.println("brak pliku mapy, generowanie...");
        String mapContent = new MapGenerator().generate();
        Files.writeString(path, mapContent);
        System.out.println("wygenerowano i zapisano do: " + filePath);
        return mapContent;
    }
}

class MapGenerator {
    private static final int SIZE = 10;
    private static final char WATER = '.';

    String generate() {
        // Placeholder - zaimplementuj logikę generowania mapy
        return String.valueOf(WATER).repeat(SIZE * SIZE);
    }
}


class ConnectionManager {
    static Socket establish(GameConfig config) throws IOException {
        if (config.isServer()) {
            System.out.println("Serwer startuje na porcie " + config.port());
            ServerSocket ss = new ServerSocket(config.port());
            return ss.accept();
        } else {
            System.out.println("Klient laczy sie do " + config.host() + ":" + config.port());
            return new Socket(config.host(), config.port());
        }
    }
}


class GameSession {
    private final GameBoard board;
    private final boolean isServer;
    private final GameProtocol protocol;

    GameSession(Socket socket, GameBoard board, boolean isServer) throws IOException {
        this.board = board;
        this.isServer = isServer;
        this.protocol = new GameProtocol(socket);
    }

    void play() {
        String lastShot = null;

        if (!isServer) {
            lastShot = board.generateShot();
            protocol.send("start;" + lastShot);
        }

        int errorCount = 0;
        while (true) {
            try {
                String message = protocol.receive();
                if (message == null) break;

                GameMessage parsed = GameMessage.parse(message);

                if (!parsed.isStart()) {
                    System.out.println("WYNIK TWOJEGO STRZAŁU W " + lastShot + ": " + parsed.result().toUpperCase());
                    board.recordEnemyResult(lastShot, parsed.result());
                }

                if (parsed.isWin()) {
                    handleVictory();
                    break;
                }

                handleEnemyShot(parsed.enemyShot());

                lastShot = board.generateShot();
                String response = board.respondToShot(parsed.enemyShot(), lastShot);

                if (response.startsWith("ostatni zatopiony")) {
                    protocol.send(response);
                    handleDefeat();
                    break;
                }

                protocol.send(response);
                errorCount = 0;

            } catch (IOException e) {
                errorCount++;
                System.out.println("blad polaczenia (" + errorCount + "/3). ponawiam...");
                if (errorCount >= 3) break;
            }
        }
    }

    private void handleEnemyShot(String coord) {
        if (!coord.isBlank()) {
            System.out.println(">> PRZECIWNIK STRZELA W: " + coord);
        }
    }

    private void handleVictory() {
        System.out.println("\n--- WYGRANA! ---");
        board.displayEnemyBoard(true);
        System.out.println();
        board.displayPlayerBoard();
    }

    private void handleDefeat() {
        System.out.println("TRAFIONY OSTATNI STATEK! PRZEGRANA.");
        board.displayEnemyBoard(false);
        System.out.println();
        board.displayPlayerBoard();
    }
}


class GameProtocol {
    private final PrintWriter out;
    private final BufferedReader in;

    GameProtocol(Socket socket) throws IOException {
        this.out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true
        );
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
        );
    }

    void send(String message) {
        out.println(message);
        System.out.println("wysylam: " + message);
    }

    String receive() throws IOException {
        return in.readLine();
    }
}

record GameMessage(String result, String enemyShot) {
    boolean isStart() {
        return "start".equals(result);
    }

    boolean isWin() {
        return result != null && result.startsWith("ostatni zatopiony");
    }

    static GameMessage parse(String message) {
        String[] parts = message.split(";");
        String result = parts[0];
        String enemyShot = parts.length > 1 ? parts[parts.length - 1] : "";
        return new GameMessage(result, enemyShot);
    }
}


class GameBoard {
    private static final int SIZE = 10;
    private static final char SHIP = '#';
    private static final char WATER = '.';
    private static final char HIT_SHIP = '@';
    private static final char MISS = '~';

    private final char[][] playerGrid;
    private final char[][] enemyGrid;
    private final List<Ship> ships;
    private final Set<String> enemyShotHistory;

    GameBoard(String mapString) {
        this.playerGrid = parseMap(mapString);
        this.enemyGrid = initializeEnemyGrid();
        this.ships = detectShips();
        this.enemyShotHistory = new HashSet<>();
    }

    private char[][] parseMap(String mapString) {
        String clean = mapString.trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "");

        char[][] grid = new char[SIZE][SIZE];
        for (int i = 0; i < SIZE * SIZE; i++) {
            grid[i / SIZE][i % SIZE] = clean.charAt(i);
        }
        return grid;
    }

    private char[][] initializeEnemyGrid() {
        char[][] grid = new char[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                grid[r][c] = '.';
            }
        }
        return grid;
    }

    private List<Ship> detectShips() {
        List<Ship> detectedShips = new ArrayList<>();
        boolean[][] visited = new boolean[SIZE][SIZE];

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (playerGrid[r][c] == SHIP && !visited[r][c]) {
                    Ship ship = new Ship();
                    dfsShip(r, c, visited, ship);
                    detectedShips.add(ship);
                }
            }
        }
        return detectedShips;
    }

    private void dfsShip(int r, int c, boolean[][] visited, Ship ship) {
        if (r < 0 || r >= SIZE || c < 0 || c >= SIZE || visited[r][c] || playerGrid[r][c] != SHIP) {
            return;
        }
        visited[r][c] = true;
        ship.addCell(r, c);
        dfsShip(r + 1, c, visited, ship);
        dfsShip(r - 1, c, visited, ship);
        dfsShip(r, c + 1, visited, ship);
        dfsShip(r, c - 1, visited, ship);
    }

    String respondToShot(String coordStr, String nextShotCoord) {
        Coordinate coord = Coordinate.parse(coordStr);
        String result;

        if (enemyShotHistory.contains(coordStr)) {
            result = getRepeatShotResult(coord);
        } else {
            enemyShotHistory.add(coordStr);
            result = processNewShot(coord);
        }

        return result + ";" + nextShotCoord;
    }

    private String getRepeatShotResult(Coordinate coord) {
        char cell = playerGrid[coord.r()][coord.c()];
        if (cell == WATER || cell == MISS) return "pudło";

        Ship ship = findShipAt(coord);
        if (ship != null && ship.isSunk()) return "trafiony zatopiony";
        return "trafiony";
    }

    private String processNewShot(Coordinate coord) {
        char cell = playerGrid[coord.r()][coord.c()];

        if (cell == WATER) {
            playerGrid[coord.r()][coord.c()] = MISS;
            return "pudło";
        } else if (cell == SHIP) {
            playerGrid[coord.r()][coord.c()] = HIT_SHIP;
            Ship ship = findShipAt(coord);

            if (ship.isSunk()) {
                return areAllShipsSunk() ? "ostatni zatopiony" : "trafiony zatopiony";
            }
            return "trafiony";
        }
        return "pudło";
    }

    void recordEnemyResult(String shotCoord, String result) {
        Coordinate coord = Coordinate.parse(shotCoord);

        if (result.contains("pudło")) {
            enemyGrid[coord.r()][coord.c()] = MISS;
        } else if (result.contains("trafiony")) {
            enemyGrid[coord.r()][coord.c()] = HIT_SHIP;
            if (result.contains("zatopiony")) {
                markEnemyShipSunk(coord);
            }
        }
    }

    private void markEnemyShipSunk(Coordinate startCoord) {
        Set<Coordinate> shipCells = traceShipCells(startCoord);
        for (Coordinate cell : shipCells) {
            markCellAndNeighbors(cell);
        }
    }

    private Set<Coordinate> traceShipCells(Coordinate start) {
        Set<Coordinate> cells = new HashSet<>();
        Queue<Coordinate> queue = new LinkedList<>();
        queue.add(start);
        cells.add(start);

        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        while (!queue.isEmpty()) {
            Coordinate current = queue.poll();
            for (int[] dir : directions) {
                int nr = current.r() + dir[0];
                int nc = current.c() + dir[1];
                if (isValid(nr, nc) && enemyGrid[nr][nc] == HIT_SHIP) {
                    Coordinate next = new Coordinate(nr, nc);
                    if (!cells.contains(next)) {
                        cells.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return cells;
    }

    private void markCellAndNeighbors(Coordinate cell) {
        int[][] allNeighbors = {
                {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
        };

        for (int[] dir : allNeighbors) {
            int nr = cell.r() + dir[0];
            int nc = cell.c() + dir[1];
            if (isValid(nr, nc) && enemyGrid[nr][nc] != HIT_SHIP) {
                enemyGrid[nr][nc] = MISS;
            }
        }
    }

    String generateShot() {
        System.out.println("\n--- TWOJA KOLEJ ---");
        displayEnemyBoard(false);

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("podaj koordynaty (A-J, 1-10; np A5): ");
                if (!scanner.hasNextLine()) return "A1";

                String input = scanner.nextLine().trim().toUpperCase();
                if (input.matches("^[A-J]([1-9]|10)$")) {
                    return input;
                }
                System.out.println("bledny format.");
            }
        }
    }

    void displayPlayerBoard() {
        System.out.println("Moja mapa:");
        printGrid(playerGrid);
    }

    void displayEnemyBoard(boolean revealed) {
        System.out.println("Mapa przeciwnika:");
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                char cell = enemyGrid[r][c];
                if (cell == HIT_SHIP) {
                    System.out.print(revealed ? SHIP : HIT_SHIP);
                } else if (cell == MISS) {
                    System.out.print(MISS);
                } else {
                    System.out.print(revealed ? '.' : '?');
                }
            }
            System.out.println();
        }
    }

    private void printGrid(char[][] grid) {
        for (char[] row : grid) {
            for (char cell : row) {
                System.out.print(cell);
            }
            System.out.println();
        }
    }

    private Ship findShipAt(Coordinate coord) {
        return ships.stream()
                .filter(ship -> ship.hasCellAt(coord.r(), coord.c()))
                .findFirst()
                .orElse(null);
    }

    private boolean areAllShipsSunk() {
        return ships.stream().allMatch(Ship::isSunk);
    }

    private boolean isValid(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }
}


class Ship {
    private final List<int[]> cells = new ArrayList<>();

    void addCell(int r, int c) {
        cells.add(new int[]{r, c});
    }

    boolean isSunk() {
        return cells.isEmpty();
    }

    boolean hasCellAt(int r, int c) {
        return cells.stream().anyMatch(cell -> cell[0] == r && cell[1] == c);
    }
}

record Coordinate(int r, int c) {
    static Coordinate parse(String coord) {
        int r = coord.toUpperCase().charAt(0) - 'A';
        int c = Integer.parseInt(coord.substring(1)) - 1;
        return new Coordinate(r, c);
    }
}