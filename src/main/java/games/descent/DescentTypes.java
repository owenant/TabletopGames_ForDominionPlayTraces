package games.descent;

import java.util.HashSet;

public class DescentTypes {

    public final static HashSet<Character> direction = new HashSet<Character>() {{
        add('N');
        add('S');
        add('W');
        add('E');
    }};

    public enum Campaign {
        HeirsOfBlood("data/descent/campaigns/heirsOfBlood.json");

        private String dataPath;
        Campaign(String path) {
            this.dataPath = path;
        }

        public String getDataPath() {
            return dataPath;
        }
    }

    public enum TerrainType {
        // Tile margins
        Edge,
        Open,
        Null,

        // Inside tile
        Plain,
        Water,
        Lava,
        Hazard,
        Pit,
        Block;

        public static HashSet<TerrainType> getWalkableTiles() {
            return new HashSet<TerrainType>() {{
                add(Plain);
                add(Water);
                add(Lava);
                add(Hazard);
                add(Pit);
            }};
        }

        public static HashSet<String> getWalkableStringTiles() {
            HashSet<String> walkable = new HashSet<>();
            for (TerrainType t: getWalkableTiles()) {
                walkable.add(t.name().toLowerCase());
            }
            return walkable;
        }

        public static HashSet<TerrainType> getMarginTiles() {
            return new HashSet<TerrainType>() {{
                add(Edge);
                add(Open);
                add(Null);
            }};
        }

        public static HashSet<String> getMarginStringTiles() {
            HashSet<String> margins = new HashSet<>();
            for (TerrainType t: getMarginTiles()) {
                margins.add(t.name().toLowerCase());
            }
            return margins;
        }

        public static boolean isWalkable(String terrain) {
            return terrain != null && (getWalkableStringTiles().contains(terrain));
        }

        public static boolean isInsideTile(String terrain) {
            return terrain != null && (!getMarginStringTiles().contains(terrain));
        }
    }
}