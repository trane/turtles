package slamdata.engine

import scalaz._

import scalaz.std.string._
import scalaz.std.list._
import scalaz.std.map._

import slamdata.engine.fp._
import slamdata.engine.fs.Path

import slamdata.engine.analysis._
import fixplate._


sealed trait LogicalPlan[+A] {
  import LogicalPlan._

  def fold[Z](
      read:       Path  => Z, 
      constant:   Data  => Z,
      join:       (A, A, JoinType, JoinRel, A, A) => Z,
      invoke:     (Func, List[A]) => Z,
      free:       Symbol  => Z,
      let:        (Map[Symbol, A], A) => Z
    ): Z = this match {
    case Read0(x)              => read(x)
    case Constant0(x)          => constant(x)
    case Join0(left, right, 
              tpe, rel, 
              lproj, rproj)   => join(left, right, tpe, rel, lproj, rproj)
    case Invoke0(func, values) => invoke(func, values)
    case Free0(name)           => free(name)
    case Let0(bind, in)        => let(bind, in)
  }
}

object LogicalPlan {
  implicit val LogicalPlanTraverse = new Traverse[LogicalPlan] {
    def traverseImpl[G[_], A, B](fa: LogicalPlan[A])(f: A => G[B])(implicit G: Applicative[G]): G[LogicalPlan[B]] = {
      fa match {
        case x @ Read0(_) => G.point(x)
        case x @ Constant0(_) => G.point(x)
        case Join0(left, right, tpe, rel, lproj, rproj) => 
          G.apply4(f(left), f(right), f(lproj), f(rproj))(Join0(_, _, tpe, rel, _, _))
        case Invoke0(func, values) => G.map(Traverse[List].sequence(values.map(f)))(Invoke0(func, _))
        case x @ Free0(_) => G.point(x)
        case Let0(let0, in0) => {
          type MapSymbol[X] = Map[Symbol, X]

          val let: G[Map[Symbol, B]] = Traverse[MapSymbol].sequence(let0.mapValues(f))
          val in: G[B] = f(in0)

          G.apply2(let, in)(Let0(_, _))
        }
      }
    }

    override def map[A, B](v: LogicalPlan[A])(f: A => B): LogicalPlan[B] = {
      v match {
        case x @ Read0(_) => x
        case x @ Constant0(_) => x
        case Join0(left, right, tpe, rel, lproj, rproj) =>
          Join0(f(left), f(right), tpe, rel, f(lproj), f(rproj))
        case Invoke0(func, values) => Invoke0(func, values.map(f))
        case x @ Free0(_) => x
        case Let0(let, in) => Let0(let.mapValues(f), f(in))
      }
    }

    override def foldMap[A, B](fa: LogicalPlan[A])(f: A => B)(implicit F: Monoid[B]): B = {
      fa match {
        case x @ Read0(_) => F.zero
        case x @ Constant0(_) => F.zero
        case Join0(left, right, tpe, rel, lproj, rproj) =>
          F.append(F.append(f(left), f(right)), F.append(f(lproj), f(rproj)))
        case Invoke0(func, values) => Foldable[List].foldMap(values)(f)
        case x @ Free0(_) => F.zero
        case Let0(let, in) => {
          type MapSymbol[X] = Map[Symbol, X]

          F.append(Foldable[MapSymbol].foldMap(let)(f), f(in))
        }
      }
    }

    override def foldRight[A, B](fa: LogicalPlan[A], z: => B)(f: (A, => B) => B): B = {
      fa match {
        case x @ Read0(_) => z
        case x @ Constant0(_) => z
        case Join0(left, right, tpe, rel, lproj, rproj) =>
          f(left, f(right, f(lproj, f(rproj, z))))
        case Invoke0(func, values) => Foldable[List].foldRight(values, z)(f)
        case x @ Free0(_) => z
        case Let0(let, in) => {
          type MapSymbol[X] = Map[Symbol, X]

          Foldable[MapSymbol].foldRight(let, f(in, z))(f)
        }
      }
    }
  }
  implicit val ShowLogicalPlan: Show[LogicalPlan[_]] = new Show[LogicalPlan[_]] {
    override def show(v: LogicalPlan[_]): Cord = v match {
      case Read0(name) => Cord("Read(" + name + ")")
      case Constant0(data) => Cord(data.toString)
      case Join0(left, right, tpe, rel, lproj, rproj) => Cord("Join(" + tpe + ")")
      case Invoke0(func, values) => Cord("Invoke(" + func.name + ")")
      case Free0(name) => Cord(name.toString)
      case Let0(let, in) => Cord("Let(" + let.keys.mkString(", ") + ")")
    }
  }
  implicit val EqualFLogicalPlan = new fp.EqualF[LogicalPlan] {
    def equal[A](v1: LogicalPlan[A], v2: LogicalPlan[A])(implicit A: Equal[A]): Boolean = (v1, v2) match {
      case (Read0(n1), Read0(n2)) => n1 == n2
      case (Constant0(d1), Constant0(d2)) => d1 == d2
      case (Join0(l1, r1, tpe1, rel1, lproj1, rproj1), 
            Join0(l2, r2, tpe2, rel2, lproj2, rproj2)) => 
        A.equal(l1, l2) && A.equal(r1, r2) && A.equal(lproj1, lproj2) && A.equal(rproj1, rproj2) && tpe1 == tpe2
      case (Invoke0(f1, v1), Invoke0(f2, v2)) => Equal[List[A]].equal(v1, v2) && f1 == f2
      case (Free0(n1), Free0(n2)) => n1 == n2
      case (Let0(l1, i1), Let0(l2, i2)) => A.equal(i1, i2) && Equal[Map[Symbol, A]].equal(l1, l2)
      case _ => false
    }
  }

  private case class Read0(path: Path) extends LogicalPlan[Nothing]
  object Read {
    def apply(path: Path): Term[LogicalPlan] = 
      Term[LogicalPlan](new Read0(path))
    
    def unapply(t: Term[LogicalPlan]): Option[Path] = 
      t.unFix match {
        case Read0(path) => Some(path)
        case _ => None
      }

    object Attr {
      import slamdata.engine.analysis.fixplate.{Attr => FAttr}

      def unapply[A](a: FAttr[LogicalPlan, A]): Option[Path] = 
        a.unFix.unAnn match {
          case Read0(path) => Some(path)
          case _ => None
        }
    }
  }
  
  private case class Constant0(data: Data) extends LogicalPlan[Nothing]
  object Constant {
    def apply(data: Data): Term[LogicalPlan] = 
      Term[LogicalPlan](Constant0(data))

    def unapply(t: Term[LogicalPlan]): Option[Data] = 
      t.unFix match {
        case Constant0(data) => Some(data)
        case _ => None
      }

    object Attr {
      import slamdata.engine.analysis.fixplate.{Attr => FAttr}

      def unapply[A](a: FAttr[LogicalPlan, A]): Option[Data] = 
        a.unFix.unAnn match {
          case Constant0(data) => Some(data)
          case _ => None
        }
    }
  }

  private case class Join0[A](left: A, right: A, 
                               joinType: JoinType, joinRel: JoinRel, 
                               leftProj: A, rightProj: A) extends LogicalPlan[A]
  object Join {
    def apply(left: Term[LogicalPlan], right: Term[LogicalPlan], 
               joinType: JoinType, joinRel: JoinRel, 
               leftProj: Term[LogicalPlan], rightProj: Term[LogicalPlan]): Term[LogicalPlan] = 
      Term[LogicalPlan](Join0(left, right, joinType, joinRel, leftProj, rightProj))

    def unapply(t: Term[LogicalPlan]): Option[(Term[LogicalPlan], Term[LogicalPlan], JoinType, JoinRel, Term[LogicalPlan], Term[LogicalPlan])] = 
      t.unFix match {
        case Join0(left, right, joinType, joinRel, leftProj, rightProj) => Some((left, right, joinType, joinRel, leftProj, rightProj))
        case _ => None
      }

    object Attr {
      import slamdata.engine.analysis.fixplate.{Attr => FAttr}

      def unapply[A](a: FAttr[LogicalPlan, A]): Option[(FAttr[LogicalPlan, A], FAttr[LogicalPlan, A], JoinType, JoinRel, FAttr[LogicalPlan, A], FAttr[LogicalPlan, A])] = 
        a.unFix.unAnn match {
          case Join0(left, right, joinType, joinRel, leftProj, rightProj) => Some((left, right, joinType, joinRel, leftProj, rightProj))
          case _ => None
        }
    }
  }

  private case class Invoke0[A](func: Func, values: List[A]) extends LogicalPlan[A]
  object Invoke {
    def apply(func: Func, values: List[Term[LogicalPlan]]): Term[LogicalPlan] = 
      Term[LogicalPlan](Invoke0(func, values))

    def unapply(t: Term[LogicalPlan]): Option[(Func, List[Term[LogicalPlan]])] = 
      t.unFix match {
        case Invoke0(func, values) => Some((func, values))
        case _ => None
      }

    object Attr {
      import slamdata.engine.analysis.fixplate.{Attr => FAttr}

      def unapply[A](a: FAttr[LogicalPlan, A]): Option[(Func, List[FAttr[LogicalPlan, A]])] = 
        a.unFix.unAnn match {
          case Invoke0(func, values) => Some((func, values))
          case _ => None
        }
    }
  }

  private case class Free0(name: Symbol) extends LogicalPlan[Nothing]
  object Free {
    def apply(name: Symbol): Term[LogicalPlan] = 
      Term[LogicalPlan](Free0(name))
    
    def unapply(t: Term[LogicalPlan]): Option[Symbol] = 
      t.unFix match {
        case Free0(name) => Some(name)
        case _ => None
      }

    object Attr {
      import slamdata.engine.analysis.fixplate.{Attr => FAttr}

      def unapply[A](a: FAttr[LogicalPlan, A]): Option[Symbol] = 
        a.unFix.unAnn match {
          case Free0(name) => Some(name)
          case _ => None
        }
    }
  }

  private case class Let0[A](let: Map[Symbol, A], in: A) extends LogicalPlan[A]
  object Let {
    def apply(let: Map[Symbol, Term[LogicalPlan]], in: Term[LogicalPlan]): Term[LogicalPlan] = 
      Term[LogicalPlan](Let0(let, in))
    
    def unapply(t: Term[LogicalPlan]): Option[(Map[Symbol, Term[LogicalPlan]], Term[LogicalPlan])] = 
      t.unFix match {
        case Let0(let, in) => Some((let, in))
        case _ => None
      }

    object Attr {
      import slamdata.engine.analysis.fixplate.{Attr => FAttr}

      def unapply[A](a: FAttr[LogicalPlan, A]): Option[(Map[Symbol, FAttr[LogicalPlan, A]], FAttr[LogicalPlan, A])] = 
        a.unFix.unAnn match {
          case Let0(let, in) => Some((let, in))
          case _ => None
        }
    }
  }
  type LPTerm = Term[LogicalPlan]

  type LP = LogicalPlan[LPTerm]

  type LPAttr[A] = Attr[LogicalPlan, A]

  type LPPhase[A, B] = Phase[LogicalPlan, A, B]

  // TODO: remove these synonyms
  def read(resource: Path): LPTerm = Read(resource)
  def constant(data: Data): LPTerm = Constant(data)
  def join(left: LPTerm, right: LPTerm, joinType: JoinType, joinRel: JoinRel, leftProj: LPTerm, rightProj: LPTerm): LPTerm = 
    Join(left, right, joinType, joinRel, leftProj, rightProj)
  def invoke(func: Func, values: List[LPTerm]): LPTerm = Invoke(func, values)
  def free(symbol: Symbol): Term[LogicalPlan] = Free(symbol)
  def let(let: Map[Symbol, Term[LogicalPlan]], in: Term[LogicalPlan]): Term[LogicalPlan] = Let(let, in)

  implicit val LogicalPlanBinder: Binder[LogicalPlan, ({type f[A]=Map[Symbol, Attr[LogicalPlan, A]]})#f] = {
    type AttrLogicalPlan[X] = Attr[LogicalPlan, X]

    type MapSymbol[X] = Map[Symbol, AttrLogicalPlan[X]]    

    new Binder[LogicalPlan, MapSymbol] {
      val bindings = new NaturalTransformation[AttrLogicalPlan, MapSymbol] {
        def empty[A]: MapSymbol[A] = Map()

        def apply[X](plan: Attr[LogicalPlan, X]): MapSymbol[X] = {
          plan.unFix.unAnn.fold[MapSymbol[X]](
            read      = _ => empty,
            constant  = _ => empty,
            join      = (_, _, _, _, _, _) => empty,
            invoke    = (_, _) => empty,
            free      = _ => empty,
            let       = (let, attr) => let
          )
        }
      }

      val subst = new NaturalTransformation[`AttrF * G`, Subst] {
        def apply[Y](fa: `AttrF * G`[Y]): Subst[Y] = {
          val (attr, map) = fa

          attr.unFix.unAnn.fold[Subst[Y]](
            read      = _ => None,
            constant  = _ => None,
            join      = (_, _, _, _, _, _) => None,
            invoke    = (_, _) => None,
            free      = symbol => map.get(symbol).map(p => (p, new Forall[Unsubst] { def apply[A] = { (a: A) => attrK(free(symbol), a) } })),
            let       = (_, _) => None
          )
        }
      }
    }
  }

  def lpBoundPhase[M[_], A, B](phase: PhaseM[M, LogicalPlan, A, B])(implicit M: Functor[M]): PhaseM[M, LogicalPlan, A, B] = {
    type MapSymbol[A] = Map[Symbol, Attr[LogicalPlan, A]]

    implicit val sg = Semigroup.lastSemigroup[Attr[LogicalPlan, A]]

    bound[M, LogicalPlan, MapSymbol, A, B](phase)(M, LogicalPlanTraverse, Monoid[MapSymbol[A]], LogicalPlanBinder)
  }

  def lpBoundPhaseE[E, A, B](phase: PhaseE[LogicalPlan, E, A, B]): PhaseE[LogicalPlan, E, A, B] = {
    type EitherE[A] = E \/ A

    lpBoundPhase[EitherE, A, B](phase)
  }

  sealed trait JoinType
  object JoinType {
    case object Inner extends JoinType
    case object LeftOuter extends JoinType
    case object RightOuter extends JoinType
    case object FullOuter extends JoinType
  }

  sealed trait JoinRel
  object JoinRel {
    case object Eq extends JoinRel
    case object Neq extends JoinRel
    case object Lt extends JoinRel
    case object Lte extends JoinRel
    case object Gt extends JoinRel
    case object Gte extends JoinRel
  }
  
  
  object Extractors {

    object HasAnn {
      def unapply[A](v: Attr[LogicalPlan, A]): Option[A] = Some(v.unFix.attr)
    }

  }
}

