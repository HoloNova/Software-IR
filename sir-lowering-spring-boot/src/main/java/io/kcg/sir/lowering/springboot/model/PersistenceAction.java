package io.kcg.sir.lowering.springboot.model;

public enum PersistenceAction {
   INSERT,
   UPDATE,
   /** Removes the stored row of the target variable. */
   DELETE;
}
