package connectivity.concurrent.tree

import connectivity.*
import connectivity.NO_EDGE
import connectivity.sequential.tree.TreeDynamicConnectivity
import kotlin.random.Random

class FineGrainedETTNode(val priority: Int, isVertex: Boolean = true, treeEdge: Edge = NO_EDGE) {
    @Volatile
    var parent: FineGrainedETTNode? = null
    var left: FineGrainedETTNode? = null
    var right: FineGrainedETTNode? = null
    var size: Int = 1
    val nonTreeEdges: SequentialEdgeSet? = if (isVertex) SequentialEdgeSet() else null // for storing non-tree edges in general case
    var hasNonTreeEdges: Boolean = false // for traversal
    var currentLevelTreeEdge: Edge = treeEdge
    var hasCurrentLevelTreeEdges: Boolean = currentLevelTreeEdge != NO_EDGE
}

// Sequential version, but with concurrent map to allow fine-grained locking. Is not a correct concurrent ETT.
class FineGrainedEulerTourTree(val size: Int) : TreeDynamicConnectivity {
    private val nodes: Array<FineGrainedETTNode>
    private val edgeToNode = ConcurrentEdgeMap<FineGrainedETTNode>()
    private val random = Random

    init {
        // priorities for vertices are numbers in [0, size)
        // priorities for edges are random numbers in [size, 11 * size)
        // priorities for nodes are less so that roots will be always vertices, not edges
        val priorities = MutableList(size) { it }
        priorities.shuffle(random)
        nodes = Array(size) { FineGrainedETTNode(priorities[it]) }
    }

    override fun addEdge(u: Int, v: Int) = addEdge(u, v, true)

    fun addEdge(u: Int, v: Int, isCurrentLevelTreeEdge: Boolean) {
        val uNode = nodes[u]
        val vNode = nodes[v]

        // rotate tours so that u and v become first nodes of the tours
        makeFirst(uNode)
        makeFirst(vNode)

        val uRoot = root(uNode)
        val vRoot = root(vNode)

        val uvEdge = makeDirectedEdge(u, v)
        val vuEdge = makeDirectedEdge(v, u)

        // create nodes corresponding to two directed copies of the new edge
        val uvNode = FineGrainedETTNode(size + random.nextInt(10 * size), false, if (isCurrentLevelTreeEdge) uvEdge else NO_EDGE)
        val vuNode = FineGrainedETTNode(size + random.nextInt(10 * size), false, if (isCurrentLevelTreeEdge) vuEdge else NO_EDGE)
        edgeToNode[uvEdge] = uvNode
        edgeToNode[vuEdge] = vuNode

        // merge (u,v), (v,u) edges and tours
        merge(merge(uRoot, uvNode), merge(vRoot, vuNode))
    }

    override fun removeEdge(u: Int, v: Int) {
        val uvEdge = makeDirectedEdge(u, v)
        val vuEdge = makeDirectedEdge(v, u)

        val edgeNode = edgeToNode[uvEdge]!!
        val reverseEdgeNode = edgeToNode[vuEdge]!!

        // get positions of the edge nodes in the tree
        var leftPosition = edgeNode.position()
        var rightPosition = reverseEdgeNode.position()
        if (leftPosition > rightPosition) {
            val tmp = rightPosition
            rightPosition = leftPosition
            leftPosition = tmp
        }

        val root = root(u)
        // cut the [leftPosition, rightPosition] segment out of the tree
        var div1 = split(root, rightPosition + 1)
        div1.first = split(div1.first, rightPosition).first // forget (v, u)
        val div2 = split(div1.first, leftPosition)
        val component1 = merge(div2.first, div1.second)
        val component2 = split(div2.second, 1).second // forget (u, v)

        // roots should not have parents
        component1?.parent = null
        component2?.parent = null

        // remove two directed copies of the deleted edge
        edgeToNode.remove(uvEdge)
        edgeToNode.remove(vuEdge)
    }

    override fun connected(u: Int, v: Int): Boolean = root(u) == root(v)

    fun root(u: Int): FineGrainedETTNode = root(nodes[u])

    fun node(u: Int): FineGrainedETTNode = nodes[u]

    private fun root(n: FineGrainedETTNode): FineGrainedETTNode {
        var node = n
        var parent = node.parent
        while (parent != null) {
            node = parent
            parent = node.parent
        }
        return node
    }

    // [prefix, node, suffix] -> [node, suffix, prefix] (rotation)
    private fun makeFirst(node: FineGrainedETTNode) {
        val root = root(node)
        val position = node.position()
        val div = split(root, position) // ([prefix], [node, suffix])
        merge(div.second, div.first)
    }

    private class SplitResults(var first: FineGrainedETTNode?, var second: FineGrainedETTNode?)

    /**
     * Splits the tree into two.
     * [sizeLeft] is the number of nodes that should go to the left tree
     */
    private fun split(node: FineGrainedETTNode?, sizeLeft: Int): SplitResults {
        if (node == null) return SplitResults(null, null)

        val toTheLeft = 1 + (node.left?.size ?: 0)
        return if (toTheLeft <= sizeLeft) {
            // node goes to the left part
            val division = split(node.right, sizeLeft - toTheLeft)
            node.right = division.first
            node.right?.parent = node
            node.recalculate()
            division.first = node
            division
        } else {
            // node goes to the right part
            val division = split(node.left, sizeLeft)
            node.left = division.second
            node.left?.parent = node
            node.recalculate()
            division.second = node
            division
        }
    }

    private fun merge(a: FineGrainedETTNode?, b: FineGrainedETTNode?): FineGrainedETTNode? {
        if (a == null) return b
        if (b == null) return a
        return if (a.priority < b.priority) {
            a.right = merge(a.right, b)
            a.right?.parent = a
            a.recalculate()
            a
        } else {
            b.left = merge(a, b.left)
            b.left?.parent = b
            b.recalculate()
            b
        }
    }

    /// from 0 to n - 1
    private fun FineGrainedETTNode.position(): Int {
        var position = (this.left?.size ?: 0)
        var current = this
        while (true) {
            val parent = current.parent ?: break
            if (current == parent.right) // is right child
                position += 1 + (parent.left?.size ?: 0)
            current = parent
        }
        return position
    }
}

internal inline fun FineGrainedETTNode.recalculate() {
    size = 1 + (left?.size ?: 0) + (right?.size ?: 0)
    hasNonTreeEdges = (nonTreeEdges?.isNotEmpty() ?: false) || (left?.hasNonTreeEdges ?: false) || (right?.hasNonTreeEdges ?: false)
    hasCurrentLevelTreeEdges = currentLevelTreeEdge != NO_EDGE || (left?.hasCurrentLevelTreeEdges ?: false) || (right?.hasCurrentLevelTreeEdges ?: false)
}

internal fun FineGrainedETTNode.recalculateUp() {
    recalculate()
    parent?.recalculateUp()
}

internal inline fun FineGrainedETTNode.update(body: FineGrainedETTNode.() -> Unit) {
    body()
    recalculateUp()
}