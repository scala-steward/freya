package freya

import freya.K8sNamespace.AllNamespaces
import freya.OperatorCfg.Crd
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidateCfgTest extends AnyFlatSpec with Matchers {

  it should "validate Config" in {
      //given
      val cfg = Crd(classOf[Kerb], AllNamespaces, prefix)
      //when
      val v = cfg.validate
      //then
      v.isRight should ===(true)
    }

  it should "validate kind" in {
      //given
      val cfg = Crd(null, AllNamespaces, "")
      //when
      val v = cfg.validate
      //then
      v.isLeft should ===(true)
    }

  it should "validate prefix" in {
      //given
      val cfg = Crd(classOf[Kerb], AllNamespaces, "")
      //when
      val v = cfg.validate
      //then
      v.isLeft should ===(true)

      //given
      val cfg2 = Crd(classOf[Kerb], AllNamespaces, null)
      //when
      val v2 = cfg2.validate
      //then
      v2.isLeft should ===(true)
    }
}