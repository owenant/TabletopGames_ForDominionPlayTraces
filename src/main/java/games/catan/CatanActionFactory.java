package games.catan;

import core.actions.AbstractAction;
import core.actions.ActionSpace;
import core.components.Counter;
import core.components.Deck;
import core.components.Edge;
import games.catan.actions.*;
import games.catan.components.Building;
import games.catan.components.CatanCard;
import games.catan.components.CatanTile;
import utilities.Utils;

import java.util.*;

import static games.catan.CatanConstants.HEX_SIDES;
import static games.catan.components.Building.Type.Settlement;

public class CatanActionFactory {
    /**
     * Calculates setup actions
     *
     * @return - ArrayList, various action types (unique).
     */
    static List<AbstractAction> getSetupActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        // find possible settlement locations and propose them as actions
        CatanTile[][] board = gs.getBoard();
        HashSet<Integer> settlementsAdded = new HashSet<>();
        for (int x = 0; x < board.length; x++) {
            for (int y = 0; y < board[x].length; y++) {
                CatanTile tile = board[x][y];
                // where it is legal to place tile then it can be placed from there
                if (!(tile.getTileType().equals(CatanTile.TileType.SEA) ||
                    tile.getTileType().equals(CatanTile.TileType.DESERT))) {
//                        actions.add(new BuildSettlement_v2(settlement, activePlayer));
//                        actions.add(new BuildSettlement(x, y, i, activePlayer));
                    for (int i = 0; i < HEX_SIDES; i++) {
                        Building settlement = gs.getBuilding(tile, i);
                        if (!settlementsAdded.contains(settlement.getComponentID()) && settlement.getOwnerId() == -1) {
                            if (gs.checkSettlementPlacement(settlement, gs.getCurrentPlayer())) {
                                settlementsAdded.add(settlement.getComponentID());
                                if (actionSpace.structure != ActionSpace.Structure.Deep) {  // Flat is default
                                    int[][] coords = tile.getNeighboursOnVertex(i);
                                    int edge = (HEX_SIDES+i-1)%HEX_SIDES;
                                    Edge edgeObj = gs.getRoad(settlement, tile, edge);
                                    if (edgeObj.getOwnerId() == -1) {
                                        actions.add(new PlaceSettlementWithRoad(x, y, i, edge, player));
                                        for (int k = 0; k < coords.length; k++) {
                                            int[] neighbour = coords[k];
                                            int vertex = (i + 2*(k+1)) % HEX_SIDES;
                                            edge = (HEX_SIDES+vertex-1)%HEX_SIDES;
                                            CatanTile nTile = board[neighbour[0]][neighbour[1]];
                                            edgeObj = gs.getRoad(nTile, vertex, edge);
                                            if (edgeObj != null && edgeObj.getOwnerId() == -1) {
                                                actions.add(new PlaceSettlementWithRoad(neighbour[0], neighbour[1], vertex, edge, player));
                                            }
                                        }
                                    }
                                } else {
                                    actions.add(new DeepPlaceSettlementThenRoad(x, y, i, player));
                                }
                            }
                        }
                    }
                }
            }
        }

        return actions;
    }

    /**
     * Generates PlayerTradeOffers relating to single type trades
     * i.e Lumber for Grain, Brick for Stone
     *
     * @param gs - current state
     * @return - ArrayList, PlayerTradeOffer type (unique).
     */
    static List<AbstractAction> getPlayerTradeOfferActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        HashMap<CatanParameters.Resource, Counter> resources = gs.getPlayerResources(gs.getCurrentPlayer());
        int exchangeRate = ((CatanParameters) gs.getGameParameters()).default_exchange_rate;
        int n_players = gs.getNPlayers();
        int currentPlayer = gs.getCurrentPlayer();

        for (int playerIndex = 0; playerIndex < n_players; playerIndex++) { // loop through players
            if (playerIndex != currentPlayer) { // exclude current player
                HashMap<CatanParameters.Resource, Counter> otherPlayerInventory = gs.getPlayerResources(playerIndex);
                for (Map.Entry<CatanParameters.Resource, Counter> e: resources.entrySet()) { // loop through current players resources to offer
                    for (CatanParameters.Resource resourceToRequest: CatanParameters.Resource.values()) { // loop through current players resources to request
                        if (resourceToRequest != e.getKey()) { // exclude the currently offered resource
                            int maxToRequest = otherPlayerInventory.get(resourceToRequest).getValue();
                            if (maxToRequest == 0)
                                continue;

                            HashMap<CatanParameters.Resource, Integer> resourcesOffered = new HashMap<>();
                            HashMap<CatanParameters.Resource, Integer> resourcesRequested = new HashMap<>();

                            // we simplify this to be aggressive in our initial bid, on the basis that we are open to a counter-offer.
                            // Effectively this creates one bid per player and possible pair of resources (which slightly reduces the combinatorial explosion of actions here)
                            int offerQuantity = Math.max(1, maxToRequest / exchangeRate); // offer at least one
                            offerQuantity = Math.min(offerQuantity, e.getValue().getValue()); // do not offer more than we have
                            resourcesOffered.put(e.getKey(), offerQuantity);
                            resourcesRequested.put(resourceToRequest, Math.min(maxToRequest, offerQuantity * exchangeRate));
                            actions.add(new OfferPlayerTrade(resourcesOffered, resourcesRequested, currentPlayer, playerIndex, 1)); // create the action
                        }
                    }
                }
            }
        }
        return actions;
    }

    static List<AbstractAction> getTradeReactionActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        OfferPlayerTrade offeredPlayerTrade = gs.getCurrentTradeOffer();

        actions.add(new EndNegotiation()); // rejects the trade offer
        if (offeredPlayerTrade.getNegotiationCount() < ((CatanParameters) gs.getGameParameters()).max_negotiation_count + 1) { // check that the maximum number of negotiations has not been exceeded to prevent AI looping
            actions.addAll(getResponsePlayerTradeOfferActions(gs));
        }
        actions.addAll(getAcceptTradeActions(gs));

        return actions;
    }

    static List<AbstractAction> getAcceptTradeActions(CatanGameState gs) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        OfferPlayerTrade offeredPlayerTrade = gs.getCurrentTradeOffer();

        if (gs.checkCost(offeredPlayerTrade.getResourcesRequested(), offeredPlayerTrade.otherPlayerID)) {
            actions.add(new AcceptTrade(offeredPlayerTrade.offeringPlayerID, offeredPlayerTrade.otherPlayerID,
                    offeredPlayerTrade.resourcesRequested, offeredPlayerTrade.resourcesOffered));
        }

        return actions;
    }

    /**
     * Generates PlayerTradeOffer actions that act as renegotiation on an existing trade offer
     * This function only generates actions that involve changing the quantities of the offered/requested resources for
     * single resource type trades
     *
     * @param gs - current game state
     * @return - actions as counter-offer for trade
     */
    static List<AbstractAction> getResponsePlayerTradeOfferActions(CatanGameState gs) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        OfferPlayerTrade offeredPlayerTrade = gs.getCurrentTradeOffer();
        if (offeredPlayerTrade.otherPlayerID != gs.getCurrentPlayer())
            throw new AssertionError("We should always be alternating Offer and Counter-Offer");
        HashMap<CatanParameters.Resource, Counter> playerResources = gs.getPlayerResources(gs.getCurrentPlayer());
        HashMap<CatanParameters.Resource, Integer> resourcesOffered = offeredPlayerTrade.getResourcesOffered();
        HashMap<CatanParameters.Resource, Integer> resourcesRequested = offeredPlayerTrade.getResourcesRequested();

        CatanParameters.Resource resourceOffered = resourcesOffered.keySet().iterator().next();
        CatanParameters.Resource resourceRequested = resourcesRequested.keySet().iterator().next();

        int maxRequest = gs.getPlayerResources(offeredPlayerTrade.offeringPlayerID).get(resourceRequested).getValue();
        // TODO: Once we have partial observability of player hands, we need to modify this to take account of uncertainty (add new type of UNKNOWN in result)
        for (int quantityAvailableToOffer = 1; quantityAvailableToOffer < playerResources.get(resourceRequested).getValue() + 1; quantityAvailableToOffer++) { // loop through the quantity of resources to offer
            for (int quantityAvailableToRequest = 1; quantityAvailableToRequest <= maxRequest; quantityAvailableToRequest++) { // loop to generate all possible combinations of offer for the current resource pair
                if (!(quantityAvailableToOffer == resourcesRequested.get(resourceRequested) && quantityAvailableToRequest == resourcesOffered.get(resourceOffered))) {
                    HashMap<CatanParameters.Resource, Integer> resourcesToOffer = new HashMap<>();
                    HashMap<CatanParameters.Resource, Integer> resourcesToRequest = new HashMap<>();
                    resourcesToOffer.put(resourceRequested, quantityAvailableToOffer);
                    resourcesToRequest.put(resourceOffered, quantityAvailableToRequest);
                    if (!resourcesToOffer.equals(resourcesRequested) && !resourcesToRequest.equals(resourcesOffered)) { // ensures the trade offer is not the same as the existing trade offer
                        actions.add(new OfferPlayerTrade(resourcesToOffer, resourcesToRequest, offeredPlayerTrade.getOtherPlayerID(), offeredPlayerTrade.getOfferingPlayerID(), offeredPlayerTrade.getNegotiationCount() + 1)); // create the action
                    }
                }
            }
        }


        return actions;
    }

    /**
     * Combinations of resources in hand to discard.
     * @param gs - game state
     * @param actionSpace - action space type
     * @param player - player to discard resources
     * @param nToDiscard - how many to discard
     * @return - list of actions discarding cards.
     */
    public static List<AbstractAction> getDiscardActions(CatanGameState gs, ActionSpace actionSpace, int player, int nToDiscard) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        int nResources = gs.getNResourcesInHand(player);
        int[] resIdx = new int[nResources];
        for (int i = 0; i < nResources; i++) resIdx[i] = i;
        List<int[]> combinations = Utils.generateCombinations(resIdx, nResources);
        for (int[] combination : combinations) {
            CatanParameters.Resource[] cardsToDiscard = new CatanParameters.Resource[nToDiscard];
            for (int i = 0; i < nToDiscard; i++) {
                cardsToDiscard[i] = gs.pickResourceFromHand(player, combination[i]);
            }
            actions.add(new DiscardResources(cardsToDiscard, player));
        }
        return actions;
    }

    /**
     * Calculates player's actions when robber is active
     *
     * @return - ArrayList, various action types (unique).
     */
    static List<AbstractAction> getRobberActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        CatanTile[][] board = gs.getBoard();
        for (int x = 0; x < board.length; x++) {
            for (int y = 0; y < board[x].length; y++) {
                CatanTile tile = board[x][y];
                if (!(tile.getTileType().equals(CatanTile.TileType.SEA))) {
                    if (actionSpace.structure != ActionSpace.Structure.Deep) { // Flat is default
                        HashSet<Integer> targets = new HashSet<>();
                        Building[] settlements = gs.getBuildings(tile);
                        for (Building settlement : settlements) {
                            if (settlement.getOwnerId() != -1 && settlement.getOwnerId() != gs.getCurrentPlayer()) {
                                targets.add(settlement.getOwnerId());
                            }
                        }
                        for (int target : targets) {
                            actions.add(new MoveRobberAndSteal(x, y, player, target));
                        }
                    } else {
                        // Deep: first move, then steal
                        actions.add(new MoveRobber(x, y, player));
                    }
                }
            }
        }
        return actions;
    }

    /**
     * @param gs - game state
     * @param actionSpace - action space type
     * @param player - player buying
     * @return lists all buy actions to the player; building road, settlement, city or buying a development card
     */
    public static List<AbstractAction> getBuyActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        CatanParameters catanParameters = (CatanParameters) gs.getGameParameters();
        ArrayList<AbstractAction> actions = new ArrayList<>();

        HashSet<Integer> settlementsAdded = new HashSet<>();
        HashSet<Integer> roadsAdded = new HashSet<>();
        // find possible roads, settlements and city upgrades and propose them as actions
        CatanTile[][] board = gs.getBoard();
        for (int x = 0; x < board.length; x++) {
            for (int y = 0; y < board[x].length; y++) {
                CatanTile tile = board[x][y];
                for (int i = 0; i < HEX_SIDES; i++) {
                    Building settlement = gs.getBuilding(tile, i);

                    // Roads
                    Edge edge = gs.getRoad(settlement, tile, i);
                    if (edge == null || roadsAdded.contains(edge.getComponentID())) continue;
                    roadsAdded.add(edge.getComponentID());

                    if (!(tile.getTileType().equals(CatanTile.TileType.SEA) || tile.getTileType().equals(CatanTile.TileType.DESERT))
                            && gs.checkRoadPlacement(i, tile, gs.getCurrentPlayer())) {
                        if (gs.checkCost(catanParameters.costMapping.get(CatanParameters.ActionType.Road), player)
                                && !gs.playerTokens.get(player).get(CatanParameters.ActionType.Road).isMaximum()) {
                            actions.add(new BuildRoad(x, y, i, player, false));
                        }
                    }

                    if (settlementsAdded.contains(settlement.getComponentID())) continue;
                    settlementsAdded.add(settlement.getComponentID());

                    // Settlements
                    // where it is legal to place tile then it can be placed from there
                    if (!(tile.getTileType().equals(CatanTile.TileType.SEA) || tile.getTileType().equals(CatanTile.TileType.DESERT))
                            && gs.checkSettlementPlacement(settlement, gs.getCurrentPlayer())) {
                        if (gs.checkCost(catanParameters.costMapping.get(CatanParameters.ActionType.Settlement), player)
                                && !gs.playerTokens.get(player).get(CatanParameters.ActionType.Settlement).isMaximum()) {
                            actions.add(new BuildSettlement(x, y, i, player, false));
                        }
                    }

                    // Cities
                    if (settlement.getOwnerId() == player && settlement.getBuildingType() == Settlement) {
                        if (gs.checkCost(catanParameters.costMapping.get(CatanParameters.ActionType.City), player)
                                && !gs.playerTokens.get(player).get(CatanParameters.ActionType.City).isMaximum()) {
                            actions.add(new BuildCity(x, y, i, player));
                        }
                    }
                }
            }
        }
        // Dev cards
        if (gs.checkCost(catanParameters.costMapping.get(CatanParameters.ActionType.DevCard), player)
                && gs.devCards.getSize() > 0) {
            actions.add(new BuyDevelopmentCard(player));
        }
        return actions;
    }

    public static List<AbstractAction> getDevCardActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        // Player can play one dev card
        ArrayList<AbstractAction> actions = new ArrayList<>();

        // get playerHand; for each card add a new action
        Deck<CatanCard> playerDevDeck = gs.playerDevCards.get(player);

        for (CatanCard c : playerDevDeck.getComponents()) {
            // avoid playing a card that has been bought in the same turn
            if (c.turnCardWasBought == gs.getTurnCounter()) {
                continue;
            }
            // victory points are automatically revealed once a player has 10+ points
            if (c.cardType == CatanCard.CardType.KNIGHT_CARD) {
                actions.add(new PlayKnightCard(player));
            }
            if (c.cardType == CatanCard.CardType.MONOPOLY) {
                for (CatanParameters.Resource resource : CatanParameters.Resource.values()) {
                    actions.add(new Monopoly(resource, player));
                }
            }
            if (c.cardType == CatanCard.CardType.YEAR_OF_PLENTY) {
                List<CatanParameters.Resource> resourcesAvailable = new ArrayList<>();

                for (CatanParameters.Resource res: CatanParameters.Resource.values()) {
                    if (gs.resourcePool.get(res).getValue() > 0)
                        for (int i = 0; i < ((CatanParameters)gs.getGameParameters()).nResourcesYoP; i++) {  // TODO this loop not needed if Utils.generateCombinations allows repetitions
                            resourcesAvailable.add(res);
                        }
                }

                int[] resIdx = new int[resourcesAvailable.size()];
                for (int i = 0; i < resourcesAvailable.size(); i++) {
                    resIdx[i] = i;
                }
                List<int[]> combinations = Utils.generateCombinations(resIdx, ((CatanParameters)gs.getGameParameters()).nResourcesYoP);
                for (int[] combo: combinations) {
                    CatanParameters.Resource[] resources = new CatanParameters.Resource[combo.length];
                    for (int i = 0; i < combo.length; i++) {
                        resources[i] = resourcesAvailable.get(combo[i]);
                    }
                    actions.add(new YearOfPlenty(resources, player));
                }
            }
            if (c.cardType == CatanCard.CardType.ROAD_BUILDING) {
                // Identify all possible roads to build  // TODO multiples
                CatanTile[][] board = gs.getBoard();
                for (int x = 0; x < board.length; x++) {
                    for (int y = 0; y < board[x].length; y++) {
                        CatanTile tile = board[x][y];
                        for (int i = 0; i < HEX_SIDES; i++) {
                            if (!(tile.getTileType().equals(CatanTile.TileType.SEA) || tile.getTileType().equals(CatanTile.TileType.DESERT))
                                    && gs.checkRoadPlacement(i, tile, player)
                                    && !gs.playerTokens.get(player).get(CatanParameters.ActionType.Road).isMaximum()) {
                                actions.add(new BuildRoad(x, y, i, player, true));
                            }
                        }
                    }
                }
            }
        }

        return actions;
    }

    /**
     * @param gs - game state
     * @param actionSpace - action space type
     * @param player - player trading
     * @return list all possible trades with the bank / harbours, using minimum exchange rate available for each resource
     * type owned by the player
     */
    public static List<AbstractAction> getDefaultTradeActions(CatanGameState gs, ActionSpace actionSpace, int player) {
        ArrayList<AbstractAction> actions = new ArrayList<>();
        HashMap<CatanParameters.Resource, Counter> playerExchangeRate = gs.getExchangeRates(player);
        // TODO: Deep: first choose resource to give, then resource to get
        for (Map.Entry<CatanParameters.Resource, Counter> res: gs.playerResources.get(player).entrySet()) {
            // give N resources (minimum exchange rate for this resource)
            CatanParameters.Resource resToGive = res.getKey();
            int nGive = playerExchangeRate.get(res.getKey()).getValue();
            int nOwned = res.getValue().getValue();
            if (nOwned >= nGive) {
                // for 1 other resource
                for (CatanParameters.Resource resToGet: CatanParameters.Resource.values()) {
                    if (resToGive != resToGet) {
                        actions.add(new DefaultTrade(resToGive, resToGet, nGive, player));
                    }
                }
            }
        }
        return actions;
    }
}
