//! Bounded, allocation-free packet ownership. Queues move leases, never payloads.
use core::{
    cell::UnsafeCell,
    ops::{Deref, DerefMut},
    sync::atomic::{AtomicBool, Ordering},
};

pub struct Slot<T> {
    used: AtomicBool,
    value: UnsafeCell<T>,
}
impl<T> Slot<T> {
    pub const fn new(value: T) -> Self {
        Self {
            used: AtomicBool::new(false),
            value: UnsafeCell::new(value),
        }
    }
}
// Only the unique lease can access a slot. Release/acquire orders reuse.
unsafe impl<T: Send> Sync for Slot<T> {}

pub struct Pool<T: 'static, const N: usize> {
    slots: [Slot<T>; N],
}
impl<T, const N: usize> Pool<T, N> {
    pub const fn new(slots: [Slot<T>; N]) -> Self {
        Self { slots }
    }
    pub fn try_acquire(&'static self) -> Option<Lease<T>> {
        for slot in &self.slots {
            if slot
                .used
                .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
                .is_ok()
            {
                return Some(Lease {
                    slot,
                    _not_sync: core::marker::PhantomData,
                });
            }
        }
        None
    }
}

pub struct Lease<T: 'static> {
    slot: &'static Slot<T>,
    _not_sync: core::marker::PhantomData<core::cell::Cell<()>>,
}
// A lease has unique access even when T itself is not Sync.
unsafe impl<T: Send> Send for Lease<T> {}
impl<T> Deref for Lease<T> {
    type Target = T;
    fn deref(&self) -> &T {
        unsafe { &*self.slot.value.get() }
    }
}
impl<T> DerefMut for Lease<T> {
    fn deref_mut(&mut self) -> &mut T {
        unsafe { &mut *self.slot.value.get() }
    }
}
impl<T> Drop for Lease<T> {
    fn drop(&mut self) {
        self.slot.used.store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exhaustion_and_reuse_preserve_exclusive_ownership() {
        static POOL: Pool<u32, 2> = Pool::new([const { Slot::new(0) }; 2]);
        let mut a = POOL.try_acquire().unwrap();
        let b = POOL.try_acquire().unwrap();
        *a = 42;
        assert!(POOL.try_acquire().is_none());
        drop(a);
        assert_eq!(*POOL.try_acquire().unwrap(), 42);
        drop(b);
    }
    #[test]
    fn concurrent_leases_never_alias() {
        static POOL: Pool<usize, 4> = Pool::new([const { Slot::new(0) }; 4]);
        std::thread::scope(|scope| {
            for _ in 0..8 {
                scope.spawn(|| {
                    for _ in 0..1000 {
                        loop {
                            if let Some(mut slot) = POOL.try_acquire() {
                                *slot += 1;
                                break;
                            }
                            std::thread::yield_now();
                        }
                    }
                });
            }
        });
        let leases: std::vec::Vec<_> = (0..4).map(|_| POOL.try_acquire().unwrap()).collect();
        assert_eq!(leases.iter().map(|l| **l).sum::<usize>(), 8000);
    }
}
